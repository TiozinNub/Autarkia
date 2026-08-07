import net.ltgt.gradle.errorprone.errorprone

plugins {
    // This plugin applies the correct loom variant based on the Minecraft version
    id("dev.kikugie.loom-back-compat")
    id("me.modmuss50.mod-publish-plugin") version "2.1.1"
    id("net.ltgt.errorprone") version "5.1.0"
}

// DO NOT set group = ...!

// Release version comes from an exact `v*` tag on HEAD; anything else is a dev build
// versioned "<mod.version>-build.<commit timestamp yyyyMMddHHmmss>" - deterministic per commit,
// so parallel CI jobs and rebuilds of the same commit agree on the version.
// The `<mod.version>-` prefix is not decoration: a bare "build.<ts>" is not valid semver, and
// Anima now ships as a NESTED jar, whose version Fabric Loader resolves as semver.
fun git(vararg args: String): String = providers.exec {
    workingDir(rootDir)
    commandLine("git", *args)
    isIgnoreExitValue = true
}.standardOutput.asText.get().trim()

val exactTag = git("describe", "--tags", "--exact-match", "--match", "v*")
val modVersion = if (exactTag.startsWith("v")) exactTag.removePrefix("v")
    else "${sc.properties.get<String>("mod.version")}-build.${git("log", "-1", "--format=%cd", "--date=format:%Y%m%d%H%M%S")}"

version = "$modVersion+${sc.current.version}"
// Resolves to "autarkia" via the `[autarkia]` table in stonecutter.properties.toml —
// `sc.branch.id` is a default property tag, so `autarkia:mod:id` shortens to `mod:id` here.
val modId: String = sc.properties["mod.id"]
base.archivesName = modId

val requiredJava: JavaVersion = when {
    sc.current.parsed >= "26.1" -> JavaVersion.VERSION_25
    sc.current.parsed >= "1.20.5" -> JavaVersion.VERSION_21
    sc.current.parsed >= "1.18" -> JavaVersion.VERSION_17
    sc.current.parsed >= "1.17" -> JavaVersion.VERSION_16
    else -> JavaVersion.VERSION_1_8
}

// This can be used for publishing on Modrinth and Curseforge
val compatibleVersions: List<String> = sc.properties.rawOrNull("mod", "mc_releases")
    ?.asList().orEmpty().map { it.toString() }

repositories {
    /**
     * Restricts dependency search of the given [groups] to the [maven URL][url],
     * improving the setup speed.
     */
    fun strictMaven(url: String, alias: String, vararg groups: String) = exclusiveContent {
        forRepository { maven(url) { name = alias } }
        filter { groups.forEach(::includeGroup) }
    }
    strictMaven("https://www.cursemaven.com", "CurseForge", "curse.maven")
    strictMaven("https://api.modrinth.com/maven", "Modrinth", "maven.modrinth")
}

dependencies {
    /**
     * Fetches only the required Fabric API modules to not waste time downloading all of them for each version.
     * @see <a href="https://github.com/FabricMC/fabric">List of Fabric API modules</a>
     */
    fun fapi(vararg modules: String) {
        for (it in modules) modImplementation(fabricApi.module(it, sc.properties["deps.fabric_api"]))
    }

    minecraft("com.mojang:minecraft:${sc.current.version}")
    // Applies Mojang Mappings on obfuscated versions
    loomx.applyMojangMappings()

    // Anima — the mind Autarkia's Persons run on. The SIBLING node: `:anima:<this version>`,
    // resolved through Stonecutter rather than hardcoded, so it can never drift onto a
    // different Minecraft version than the one being built here.
    // `namedElements` is Loom's configuration for an already-named (un-remapped) subproject
    // jar — the standard way one Fabric project depends on another in the same build.
    val anima = requireNotNull(sc.node.sibling("anima")) {
        "No `anima` sibling for ${sc.current.project} — every branch must carry the same nodes"
    }.project
    // Compile and dev-run against it, but do not nest it (decision: Luiz). Anima is a mod in
    // its own right and is downloaded as its own file; jar-in-jar would mean a player who also
    // runs a second Anima consumer carries two copies and lets Loader pick, and it would make
    // Anima's release cadence a detail of Autarkia's jar. fabric.mod.json declares the
    // dependency instead, pinned to the exact version — both are built from one commit, so a
    // mismatched pair is always a mistake and should fail loudly at load rather than subtly at
    // runtime.
    implementation(project(path = anima.path, configuration = "namedElements"))

    // night-config, for the DEV RUN only — Autarkia never names it. Anima's config machinery
    // reads and writes `config/autarkia.toml` on Autarkia's behalf, so the classes must be on the
    // classpath when the dev client/server launches, and `namedElements` publishes Anima's jar
    // without Anima's dependencies. A shipped Autarkia gets them the proper way: Anima nests
    // them, and fabric.mod.json already makes Anima a hard dependency. Not
    // `include`d here — two copies of one library is the whole problem jar-in-jar creates.
    val nightConfig: String = sc.properties["deps.night_config"]
    for (module in listOf("core", "toml")) {
        implementation("com.electronwill.night-config:$module:$nightConfig")
    }

    // Use `mod{dependency type}` even on 26.1+ - loom-back-compat converts them
    modImplementation("net.fabricmc:fabric-loader:${property("deps.fabric_loader")}")
    fapi(
        "fabric-lifecycle-events-v1", "fabric-resource-loader-v0", "fabric-content-registries-v0",
        "fabric-registry-sync-v0", "fabric-object-builder-api-v1", "fabric-rendering-v1",
        "fabric-command-api-v2", "fabric-events-interaction-v0",
        // Selection sync: the debug wand / `/autarkia select` pin is pushed S2C to drive the client
        // selection glow (networking); resync on respawn needs the entity events (entity-events).
        "fabric-networking-api-v1", "fabric-entity-events-v1"
    )

    // The optional config GUI moved to Anima with the knobs it edits: Autarkia has no
    // tunables of its own yet, so it needs neither YACL nor the Mod Menu entrypoint. When it
    // grows a KnobSet, these come back here for its screen — Anima's stays Anima's.

    // DevAuth Neo: real-account login in dev runs (enable with -Pdevauth). Runtime-only,
    // never shipped. No builds exist below 1.21.11 - those nodes stay offline-mode.
    when {
        sc.current.parsed >= "26.1" -> "1.1.1"
        sc.current.parsed >= "1.21.11" -> "1.0.2"
        else -> null
    }?.let { modLocalRuntime("maven.modrinth:dev-auth-neo:$it") }

    // core/-layer unit tests: plain JUnit, headless — no Minecraft on the test classpath.
    // Anima's brain test doubles come from its testFixtures: the chop tests drive a FakeContext,
    // and re-implementing that harness per consumer is the duplication the split exists
    // to avoid. Fidelia will want the same.
    // Named by configuration rather than testFixtures(project(...)): the main dependency above
    // already takes Anima through an explicit `namedElements` configuration, and mixing that
    // with normal variant selection collides on the project's own capability.
    testImplementation(project(path = anima.path, configuration = "testFixturesRuntimeElements"))
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    errorprone("com.google.errorprone:error_prone_core:2.50.0")
}

tasks.named<Test>("test") {
    useJUnitPlatform()

    // See the same block in anima/build.gradle.kts: a project resolves to its jar on the test
    // classpath, and on a Mojang-mapped node that jar carries intermediary names, so this node's own
    // named classes have to come first.
    classpath = sourceSets["main"].output + classpath

    // See the same block in anima/build.gradle.kts: ArchitectureTest reads the BRANCH's source as
    // text (`autarkia/src`), because that is the one form that still carries `//?` directives and
    // the one form every node is generated from.
    val branchSources = sc.branch.project.file("src/main/java")
    systemProperty("autarkia.arch.sourceRoot", branchSources.absolutePath)
    inputs.dir(branchSources).withPropertyName("branchSources").withPathSensitivity(PathSensitivity.RELATIVE)

    // See the same block in anima/build.gradle.kts: JarContentsTest inspects the artifact that
    // actually ships (`remapJar` on the Mojang-mapped nodes, `jar` on the unobfuscated ones), and
    // `AbstractArchiveTask` rather than `Jar` because Loom's RemapJarTask is not the `Jar` a build
    // script names by default.
    val shippedJar = (if (tasks.names.contains("remapJar")) tasks.named<AbstractArchiveTask>("remapJar")
                      else tasks.named<AbstractArchiveTask>("jar")).flatMap { it.archiveFile }
    dependsOn(shippedJar)
    inputs.file(shippedJar).withPropertyName("shippedJar").withPathSensitivity(PathSensitivity.NAME_ONLY)
    // A plain String, not a jvmArgumentProviders lambda — a lambda in a build script captures the
    // script object and the configuration cache cannot serialize that.
    systemProperty("autarkia.jar", shippedJar.get().asFile.absolutePath)
    systemProperty("autarkia.version", modVersion)
}

// Warnings are errors — see the same block in anima/build.gradle.kts for what each exclusion buys,
// why the list is `all` minus four rather than four named checks, and why the last two are named
// only on the JDKs that have them. `-Plint=off` opts out.
tasks.withType<JavaCompile>().configureEach {
    val lint = providers.gradleProperty("lint").orNull != "off"
    if (lint) {
        val muted = buildList {
            add("classfile")
            add("deprecation")
            if (requiredJava >= JavaVersion.VERSION_21) add("this-escape")
            if (requiredJava >= JavaVersion.VERSION_22) add("dangling-doc-comments")
        }
        options.compilerArgs.addAll(
            listOf(muted.joinToString(",-", prefix = "-Xlint:all,-"), "-Werror")
        )
    }
    options.errorprone {
        isEnabled = lint
        // Mixins are excluded — see anima/build.gradle.kts for why an injector's unread parameter
        // is not dead code.
        excludedPaths = ".*/mixin/.*"
        disableAllWarnings = true
    }
}

// See the same block in anima/build.gradle.kts for the full story: Gradle rewrites an archive in
// place, a running dev game reads mod classes out of it lazily, and the two together corrupt
// every class the live JVM has not loaded yet. Unlinking first puts the rewrite on a new inode,
// which the running game's open file descriptor never follows.
tasks.withType<Jar>().configureEach {
    doFirst { archiveFile.get().asFile.delete() }
}

loom {
    // The BRANCH's own source dir (`autarkia/src`) — `sc.branch.project` is the safe way to
    // say `project(":autarkia")` from inside a node.
    fabricModJsonPath = sc.branch.project.file("src/main/resources/fabric.mod.json") // Useful for interface injection
    accessWidenerPath = sc.process(
        sc.branch.project.file("src/main/resources/autarkia.ct"),
        "build/processed.ct"
    )

    decompilerOptions.named("vineflower") {
        options.put("mark-corresponding-synthetics", "1") // Adds names to lambdas - useful for mixins
    }

    runConfigs.all {
        preferGradleTask = true
        generateRunConfig = true
        // Shared between versions, but client and server each get their own directory.
        // -PrunDir moves it. That is what lets a SECOND, isolated server run beside the shared
        // one — its own world, port and state. scripts/server-headless.sh already offers that
        // through AUTARKIA_SERVER_DIR; without this the override reached the harness but not the
        // game, and both servers landed in run/server on top of each other.
        runDirectory = rootProject.file(providers.gradleProperty("runDir").getOrElse("run/$name"))

        // Dumps every transformed class to <runDir>/.mixin.out/ — 9-13 MB of writes per launch,
        // and useful only when you actually want to READ the bytecode a mixin produced. Opt in
        // with -Pmixindebug (scripts/{client,server}.sh --mixin-debug).
        if (providers.gradleProperty("mixindebug").isPresent) {
            jvmArguments.add("-Dmixin.debug.export=true")
        }

        // Writes every composited appearance to a PNG as it is baked, so a look can be compared
        // against the art it was composed from. Opt in with -Pappearancedump (scripts/client.sh
        // --appearance-dump); the value is a directory, defaulting to one under the run dir.
        // A baked texture lives only on the GPU and in native memory, so without this the only way
        // to check a bake is to look at it — which is no way to prove one is unchanged.
        if (providers.gradleProperty("appearancedump").isPresent) {
            val into = providers.gradleProperty("appearancedump").get()
                .ifEmpty { "${runDirectory.get().asFile}/appearance-dump" }
            jvmArguments.add("-Danima.appearance.dump=$into")
        }

        // -Pjoin=host:port sends the client straight into a server on launch, skipping the menus.
        // Vanilla's own quick-play argument, not a mod feature. It exists because anything the
        // CLIENT does (and the whole appearance bake does) cannot be checked from the headless
        // harness at all, and a check that needs someone to click through two screens first is a
        // check that does not get run.
        if (name == "client" && providers.gradleProperty("join").isPresent) {
            programArguments.add("--quickPlayMultiplayer")
            programArguments.add(providers.gradleProperty("join").get())
        }

        // Hot swap (-Photswap, or scripts/{client,server}.sh --hotswap): open a JDWP port so
        // scripts/hotswap.sh can push recompiled classes into the RUNNING game, and turn on
        // enhanced class redefinition so a swap may add methods and fields — stock HotSpot
        // allows method bodies only. Client and server get their own port so both can be
        // swapped at once. suspend=n: the game boots without waiting for anyone to attach.
        //
        // This is the SLOW profile, and so: it gives up C2 (see below) to keep the
        // world alive across a code change. The fast profile in the `else` branch is what runs
        // otherwise, and the two are mutually exclusive — enhanced redefinition refuses to start
        // on anything but G1 or Serial ("Must use the Serial or G1 GC with enhanced class
        // redefinition"), so a hot-swapping run cannot have the fast profile's GC either way.
        if (providers.gradleProperty("hotswap").isPresent) {
            jvmArguments.add("-XX:+AllowEnhancedClassRedefinition")
            jvmArguments.add("-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:${if (name == "client") 5006 else 5005}")
            // Stop the JIT at C1. Enhanced redefinition and the C2 compiler do not get along: C2
            // can be part-way through compiling a method that a swap replaces underneath it, and
            // then dies on `guarantee(...jvmti_state_changed()) failed: old method not detected`
            // (ciMethod.cpp:749) — a HARD VM abort, SIGABRT, no Minecraft crash report, just an
            // hs_err file. It killed a dev client six minutes after a swap on 2026-08-02. C1 alone
            // does not do the speculative devirtualisation that trips it. The cost is peak
            // throughput, which a dev session on a superflat test world can afford; hot swap is
            // never on in a real build.
            jvmArguments.add("-XX:TieredStopAtLevel=1")
        } else if (providers.gradleProperty("jvm").orNull != "stock") {
            // ── the fast profile: the DEFAULT for any run that is not hot-swapping ──────────
            // Opt out with -Pjvm=stock to get the bare JVM this block replaced.
            //
            // What it replaced was nothing at all: the runs set no heap and no GC, so ergonomics
            // picked -Xms 1/64 of RAM and -Xmx 1/4. On a 60 GB box that is a 968 MB heap that
            // grows to 15 GB by stop-the-world resizes — during the window you are
            // staging a scene and watching the first ticks.
            //
            // Everything here stays close to what a PLAYER runs. Mojang's own
            // version manifest ships no heap and no GC flags, so the shipping configuration is
            // default G1; this tunes G1 rather than replacing it. ZGC would cut pauses further
            // but taxes every reference read with a load barrier, and the ns/cell numbers this
            // project is tuned against would stop describing anything real.
            val heap = providers.gradleProperty("heap").getOrElse(if (name == "client") "4G" else "2G")
            jvmArguments.add("-Xms$heap")   // Xms == Xmx: never resize the heap mid-session.
            jvmArguments.add("-Xmx$heap")
            jvmArguments.add("-XX:+AlwaysPreTouch") // Fault the pages in NOW, not during a tick.

            // G1, tuned for a small heap that is collected often and briefly rather than rarely
            // and long. MaxTenuringThreshold=1 + SurvivorRatio=32: a tick's garbage is dead by
            // the next one, so promoting it to the old gen only buys a mixed collection later.
            // UnlockExperimentalVMOptions must PRECEDE G1NewSizePercent — it is an experimental
            // flag, and the JVM refuses to start on the wrong order ("The unlock option must
            // precede 'G1NewSizePercent'"), not merely on the missing one.
            jvmArguments.add("-XX:+UnlockExperimentalVMOptions")
            jvmArguments.add("-XX:+UseG1GC")
            jvmArguments.add("-XX:MaxGCPauseMillis=37")            // under one 50 ms tick
            jvmArguments.add("-XX:G1HeapRegionSize=16M")
            jvmArguments.add("-XX:G1NewSizePercent=30")
            jvmArguments.add("-XX:G1ReservePercent=20")
            jvmArguments.add("-XX:SurvivorRatio=32")
            jvmArguments.add("-XX:MaxTenuringThreshold=1")
            jvmArguments.add("-XX:G1MixedGCCountTarget=3")
            jvmArguments.add("-XX:InitiatingHeapOccupancyPercent=15")
            // Minecraft and LWJGL lean on System.gc() to reclaim direct buffers, so
            // -XX:+DisableExplicitGC (the usual recommendation) trades a pause for an eventual
            // direct-memory OOM. Making the explicit collection concurrent gets the pause back
            // without taking the reclamation away.
            jvmArguments.add("-XX:+ExplicitGCInvokesConcurrent")
            // Stop writing the hsperfdata mmap into /tmp: the write can stall on a page fault,
            // and nothing here reads it.
            jvmArguments.add("-XX:+PerfDisableSharedMem")

            // C2 declines to compile any method over 8000 bytes and never revisits it. Minecraft
            // and its mixin-woven methods have several, and they run every tick, interpreted, for
            // the whole session. Lifting the ban needs the node limits raised with it or C2 bails
            // out again halfway — and NodeLimitFudgeFactor must land between 2% and 40% of
            // MaxNodeLimit or the JVM refuses to start, so these three move together or not at all.
            jvmArguments.add("-XX:-DontCompileHugeMethods")
            jvmArguments.add("-XX:MaxNodeLimit=240000")
            jvmArguments.add("-XX:NodeLimitFudgeFactor=8000")
            // More compiled code, and now bigger methods among it, than the 240 MB default holds.
            // A full code cache silently stops compiling and the game degrades to the interpreter.
            jvmArguments.add("-XX:ReservedCodeCacheSize=512M")
            jvmArguments.add("-XX:NonNMethodCodeHeapSize=16M")
            jvmArguments.add("-XX:ProfiledCodeHeapSize=248M")
            jvmArguments.add("-XX:NonProfiledCodeHeapSize=248M")

            // Project Lilliput: 16-byte object headers down to 8. Minecraft allocates small
            // objects by the million (BlockPos, Vec3i, the nav grid's cells), so this is a
            // straight cut in footprint and, more to the point, in cache misses per scan.
            // JEP 519 made it a product flag in 25 — but it does not EXIST before 24, and an
            // unrecognised -XX flag is fatal, so the 1.21.11 node (Java 21) must not see it.
            if (requiredJava >= JavaVersion.VERSION_25) {
                jvmArguments.add("-XX:+UseCompactObjectHeaders")
            }

            // LWJGL validates every argument of every GL/AL call by default. Useful when you are
            // writing the render path, pure overhead when you are watching a Person walk.
            if (name == "client") {
                jvmArguments.add("-Dorg.lwjgl.util.NoChecks=true")
            }
        }

        // Real Microsoft-account login via DevAuth Neo. scripts/client.sh passes -Pdevauth by
        // DEFAULT now — its --offline is what drops this branch and gives you the nameless dev
        // profile the client used to start with.
        if (name == "client" && providers.gradleProperty("devauth").isPresent) {
            jvmArguments.add("-Ddevauth.enabled=1")
            providers.gradleProperty("devauth.account").orNull
                ?.let { jvmArguments.add("-Ddevauth.account=$it") }
        }
    }
}

java {
    withSourcesJar()
    targetCompatibility = requiredJava
    sourceCompatibility = requiredJava

    toolchain {
        vendor = JvmVendorSpec.ADOPTIUM
        languageVersion = JavaLanguageVersion.of(requiredJava.majorVersion)
    }
}

// Under -Photswap the GAME runs on the JetBrains Runtime instead of the Adoptium toolchain.
// JBR is HotSpot with DCEVM merged in, so `-XX:+AllowEnhancedClassRedefinition` (set above)
// lets a redefinition add methods and fields rather than only rewrite method bodies. Nothing
// else moves: compilation, the jars and CI stay on Adoptium, so a hot-swapped session and a
// normal one build byte-identical output. Install with scripts/install-jbr.sh.
if (providers.gradleProperty("hotswap").isPresent) {
    val jbr = javaToolchains.launcherFor {
        vendor = JvmVendorSpec.JETBRAINS
        languageVersion = JavaLanguageVersion.of(requiredJava.majorVersion)
    }
    tasks.withType<JavaExec>().matching { it.name.startsWith("run") }.configureEach {
        javaLauncher = jbr
    }
}

tasks {
    processResources {
        fun MutableMap<String, String>.register(key: String, property: String) {
            val value: String = sc.properties[property]
            inputs.property(key, value)
            set(key, value)
        }

        inputs.property("version", modVersion)
        val props = buildMap {
            register("id", "mod.id")
            register("name", "mod.name")
            put("version", modVersion)
            register("minecraft", "mod.mc_compat")
        }

        filesMatching("fabric.mod.json") { expand(props) }

        val mixinJava = "JAVA_${requiredJava.majorVersion}"
        filesMatching("*.mixins.json") { expand("java" to mixinJava) }
    }

    // The licence travels with the jar: someone who has only the file, not the repository,
    // still has the terms. TRADEMARKS.md rides along because the licences say
    // nothing about the name, so the jar would otherwise imply the name came with the code.
    named<Jar>("jar") {
        from(rootProject.file("TRADEMARKS.md"))
        from(sc.branch.project.file("LICENSE"))
    }

    register<Copy>("buildAndCollect") {
        group = "build"
        description = "Builds mod jars and copies results to `build/libs/{mod version}/`"

        inputs.property("version", modVersion)
        // loomx.mod(Sources)Jar returns the jar task for the applied loom variant
        from(loomx.modJar.flatMap { it.archiveFile }, loomx.modSourcesJar.flatMap { it.archiveFile })
        into(rootProject.layout.buildDirectory.file("libs/$modVersion"))
    }
}

// `./gradlew publishMods` from the root publishes every version node.
// Without MODRINTH_TOKEN set this is a dry run (files land in build/publishMods/).
publishMods {
    file = loomx.modJar.flatMap { it.archiveFile }
    displayName = "Autarkia $modVersion for MC ${sc.current.version}"
    version = project.version.toString()
    changelog = providers.environmentVariable("CHANGELOG").orElse("See the commit history.")
    type = ALPHA
    modLoaders.add("fabric")
    dryRun = providers.environmentVariable("MODRINTH_TOKEN").orNull == null

    modrinth {
        // Resolves to uj0QkqNF via the `[autarkia]` table
        val modrinthId: String = sc.properties["publish.modrinth_id"]
        projectId = modrinthId
        // Anima is no longer nested, so the page has to say it is required — otherwise the
        // first thing a downloader meets is a missing-dependency crash.
        requires { id = "l8eKuisB" }
        accessToken = providers.environmentVariable("MODRINTH_TOKEN")
        minecraftVersions.addAll(compatibleVersions)
    }
}