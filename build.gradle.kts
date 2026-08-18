import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import javax.imageio.ImageIO
import me.modmuss50.mpp.platforms.modrinth.ModrinthEnvironment
import net.ltgt.gradle.errorprone.errorprone

plugins {
    // This plugin applies the correct loom variant based on the Minecraft version
    id("dev.kikugie.loom-back-compat")
    id("me.modmuss50.mod-publish-plugin") version "2.1.1"
    id("net.ltgt.errorprone") version "5.1.0"
}

// DO NOT set group = ...!

// Release version comes from an exact `<modid>-v*` tag on HEAD; anything else is a dev build
// versioned "<mod.version>-build.<commit timestamp yyyyMMddHHmmss>" - deterministic per commit,
// so parallel CI jobs and rebuilds of the same commit agree on the version.
// The `<mod.version>-` prefix is not decoration: a bare "build.<ts>" is not valid semver, and
// Fabric Loader resolves every version it reads as semver.
//
// The tag is PREFIXED with the MOD ID (`autarkia-v0.2.0`, never a bare `v0.2.0`), because each
// mod in this repo now carries its own number and is released on its own. See
// docs/superpowers/specs/2026-08-16-repo-split-design.md, slice 1.
fun git(vararg args: String): String = providers.exec {
    workingDir(rootDir)
    commandLine("git", *args)
    isIgnoreExitValue = true
}.standardOutput.asText.get().trim()

// Top-level `mod.id` in stonecutter.properties.toml. This repo has a ROOT branch and one mod,
// so there are no `[<mod>]` tables and no `sc.branch.id` tag shortening a path to reach them.
// Read before the version, which is derived from it.
val modId: String = sc.properties["mod.id"]

val tagPrefix = "$modId-v"
val exactTag = git("describe", "--tags", "--exact-match", "--match", "$tagPrefix*")
val isRelease = exactTag.startsWith(tagPrefix)

// A release is the tag's number; anything else is `<mod.version>-SNAPSHOT`. Same rule as Anima,
// and for the same reason: these are published, and a timestamped version would mint a permanent
// entry in the registry on every workstation build. The commit stamp moves to the jar manifest.
val modVersion = if (isRelease) exactTag.removePrefix(tagPrefix)
    else "${sc.properties.get<String>("mod.version")}-SNAPSHOT"

/** The commit this jar was built from — the identity `-SNAPSHOT` does not carry. */
val buildStamp = git("log", "-1", "--format=%cd", "--date=format:%Y%m%d%H%M%S")

// Anima's version is now a PIN, read from stonecutter.properties.toml, not a number recomputed
// from this repo's git tags. It could be recomputed while both mods shared a commit; they are
// separate repositories now and this repo's history says nothing about the library's.
val animaGroup = "dev.luizloyola"
val animaVersion: String = sc.properties["deps.anima"]

// The Minecraft version lives in Anima's ARTIFACT ID — see its build.gradle.kts for why it cannot
// live in the version string and still be a real snapshot. Derived from this node rather than
// written down. That is what restores the one safety property lost with the sibling project
// dependency: `sc.node.sibling("anima")` made it structurally impossible to compile against a
// library built for a different Minecraft version, and a Maven coordinate can say anything at all.
// Now a 26.1.2 build can only ever ask for anima-26.1.2, and asking for a version Anima has not
// published fails the build outright instead of producing a pair that loads and misbehaves.
val animaArtifact = "anima-${sc.current.version}"

version = "$modVersion+${sc.current.version}"
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

    // ── Where Anima comes from ─────────────────────────────────────────────────────────────
    //
    // Two sources, tried in this order, and the order is the point. The local directory is what
    // the Anima-Workspace helper publishes into, so a change to the library is visible here
    // without a round trip through a server; Gitea is what a clone of this repo alone resolves
    // from. Both are restricted to Anima's group, so a typo in any other coordinate cannot
    // silently start hunting a private server for it.
    //
    // Not `mavenLocal()`: ~/.m2 is machine-global and parallel sessions share one
    // checkout on this box, so two sessions publishing different Animas would poison each other
    // with nothing in any log to say where the wrong jar came from.
    // `content { includeGroup(...) }` on each rather than one `exclusiveContent` block:
    // exclusiveContent takes a SINGLE repository, and two of them both claiming the group would
    // be contradictory. This form gives the half that matters — neither repository is ever
    // searched for anything but Anima — while still letting either one satisfy it.
    maven {
        name = "LocalMaven"
        url = uri(providers.gradleProperty("localMaven")
            .getOrElse(rootProject.file("../anima/build/local-maven").path))
        content { includeGroup("dev.luizloyola") }
    }
    maven {
        name = "Gitea"
        url = uri("https://gitea.luizloyola.dev/api/packages/TiozinNub/maven")
        // The instance serves nothing anonymously (the user is `limited`), so a clone needs
        // credentials to build. Absent ones are not an error here — the local directory above
        // may well satisfy the dependency on its own.
        credentials {
            username = providers.environmentVariable("GITEA_USER").getOrElse("TiozinNub")
            password = providers.environmentVariable("GITEA_TOKEN").orNull
        }
        // not snapshotsOnly(): dev builds are snapshots but an `anima-v0.2.0` release is not,
        // and this is the only place a release would be found.
        content { includeGroup("dev.luizloyola") }
    }
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

    // Anima — the mind Autarkia's Persons run on, resolved from MAVEN the way any other
    // consumer would resolve it. It used to be the sibling Stonecutter node
    // (`project(":anima:<version>", "namedElements")`), which made it structurally impossible to
    // build against an Anima meant for a different Minecraft version. Nothing in Maven does that,
    // so the assertion below replaces the guarantee the project dependency used to give for free.
    //
    // `modImplementation`, not `implementation`: a published artifact has to be remapped to this
    // node's mappings, where a sibling's `namedElements` jar was already named.
    //
    // not `include`d (decision: Luiz). Anima is a mod in its own right and is downloaded as its
    // own file; jar-in-jar would mean a player running a second Anima consumer carries two copies
    // and lets Loader pick, and it would make Anima's release cadence a detail of this jar.
    // fabric.mod.json declares the dependency instead.
    modImplementation("$animaGroup:$animaArtifact:$animaVersion")

    // night-config is gone from here, and that is the split working. It was only ever listed
    // because `namedElements` published Anima's jar without Anima's dependencies, so the classes
    // Anima's config machinery needs to write `config/autarkia.toml` were missing from the dev
    // run's classpath. A Maven POM carries them transitively, so this mod goes back to never
    // naming a library it does not use.

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
    testImplementation(testFixtures("$animaGroup:$animaArtifact:$animaVersion"))
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
    val branchSources = rootProject.file("src/main/java")
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
    // The root project's own source dir. Was `sc.branch.project.file(...)` while this mod was a
    // branch of a shared tree; with a root branch the branch project is the root project.
    fabricModJsonPath = rootProject.file("src/main/resources/fabric.mod.json") // Useful for interface injection
    accessWidenerPath = sc.process(
        rootProject.file("src/main/resources/autarkia.ct"),
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

        // Anima's web debugger, up with every dev world. Off in anything that ships — this is the
        // launcher saying yes, not a default anybody inherits.
        //
        // Client and server get their own port for exactly the reason the JDWP ports below differ:
        // in single-player the client hosts its own integrated server, so BOTH processes start one
        // and a shared port means whichever loses the race logs "Address already in use". These are
        // defaults only — an edited web_debugger.port still wins.
        jvmArguments.add("-Danima.web_debugger.autostart=true")
        jvmArguments.add("-Danima.web_debugger.port=${if (name == "client") 25598 else 25599}")

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
        // Declared as an input in its own right: Anima's version can move while this mod's does
        // not (that is the whole point of decoupling them), and without this the task is
        // UP-TO-DATE across the change that alters the dependency it writes.
        inputs.property("anima_version", animaVersion)
        val props = buildMap {
            register("id", "mod.id")
            register("name", "mod.name")
            put("version", modVersion)
            put("anima_version", animaVersion)
            register("minecraft", "mod.mc_compat")
        }

        filesMatching("fabric.mod.json") { expand(props) }

        val mixinJava = "JAVA_${requiredJava.majorVersion}"
        filesMatching("*.mixins.json") { expand("java" to mixinJava) }
    }

    // The licence travels with the jar: someone who has only the file, not the repository,
    // still has the terms.
    //
    // licenses/ rides along because LICENSE here is the LGPL, and the LGPL is not a whole licence
    // — it is a set of additional permissions written on top of the GPL, which it incorporates by
    // reference. Shipping it alone would convey terms that point at a document the reader does not
    // have, so the GPL text travels beside it.
    //
    // NOTICE carries the prose: the name reservation, and where the Person textures came from.
    // JarContentsTest asserts it landed — a `from()` naming a missing file is a silent no-op.
    named<Jar>("jar") {
        // Which commit this is. The version string stopped saying so when dev builds became
        // `-SNAPSHOT`; `unzip -p <jar> META-INF/MANIFEST.MF` answers it. Anima-Version is here
        // because a mismatched pair is the failure mode this split introduced, and a bug report
        // that carries both jars' manifests can be diagnosed without asking anybody anything.
        manifest.attributes(
            "Implementation-Title" to (sc.properties["mod.name"] as String),
            "Implementation-Version" to modVersion,
            "Implementation-Build" to buildStamp,
            "Minecraft-Version" to sc.current.version,
            "Anima-Version" to animaVersion,
        )

        from(rootProject.file("LICENSE"))
        from(rootProject.file("NOTICE"))
        from(rootProject.file("licenses")) { into("licenses") }
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
        // Resolves to uj0QkqNF from the top-level `publish.modrinth_id`
        val modrinthId: String = sc.properties["publish.modrinth_id"]
        projectId = modrinthId
        // Anima is no longer nested, so the page has to say it is required — otherwise the
        // first thing a downloader meets is a missing-dependency crash.
        requires { id = "l8eKuisB" }
        accessToken = providers.environmentVariable("MODRINTH_TOKEN")
        minecraftVersions.addAll(compatibleVersions)
        // The page body IS DESCRIPTION.md. The plugin PATCHes it on every publish, so this repo is
        // the only place it is written — editing the page on the site is undone by the next tag.
        projectDescription = providers.fileContents(
            rootProject.layout.projectDirectory.file("DESCRIPTION.md"),
        ).asText
        // fabric.mod.json declares `"environment": "*"`; Modrinth's equivalent is both sides
        // required. Left unset it shows as "unknown", which reads as broken on the page.
        environment = ModrinthEnvironment.CLIENT_AND_SERVER
    }
}

// ── The Modrinth project icon ──────────────────────────────────────────────────────────────────
// `publishMods` uploads VERSIONS; every PROJECT-level field is ours to send. Modrinth wants 512px
// and the jar ships 128, so this upscales x4 with nearest-neighbour — pixel-identical to the 512
// export in the workspace, which is why no mod repo carries a second copy of the art.
//
// A Modrinth CDN filename is the SHA1 of the stored bytes, so an unchanged icon costs one GET and
// no upload. That is what makes this safe to hang off every node of a matrix release.
//
// The java.* types are imported at the top of this file rather than written out: inside a build
// script `java` is the JavaPluginExtension, so a fully-qualified `java.net.http...` does not
// resolve to the package at all.
val syncModrinthIcon = tasks.register("syncModrinthIcon") {
    group = "publishing"
    description = "Uploads the mod icon to Modrinth at 512px, when it differs from what is there."

    val projectId: String = sc.properties["publish.modrinth_id"]
    val iconFile = rootProject.layout.projectDirectory
        .file("src/main/resources/assets/$modId/icon.png").asFile
    val token = providers.environmentVariable("MODRINTH_TOKEN")
    val agent = "$modId-build (dev.luizloyola)"
    inputs.file(iconFile)

    doLast {
        val key = token.orNull
        if (key == null) {
            logger.lifecycle("No MODRINTH_TOKEN - icon sync skipped.")
            return@doLast
        }

        val scaled = BufferedImage(512, 512, BufferedImage.TYPE_INT_ARGB)
        scaled.createGraphics().apply {
            setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR,
            )
            drawImage(ImageIO.read(iconFile), 0, 0, 512, 512, null)
            dispose()
        }
        val png = ByteArrayOutputStream().also { ImageIO.write(scaled, "png", it) }.toByteArray()
        val sha1 = MessageDigest.getInstance("SHA-1").digest(png)
            .joinToString("") { "%02x".format(it) }

        val http = HttpClient.newHttpClient()
        val base = "https://api.modrinth.com/v2/project/$projectId"
        val live = http.send(
            HttpRequest.newBuilder(URI.create(base))
                .header("Authorization", key).header("User-Agent", agent).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        check(live.statusCode() == 200) { "Modrinth GET -> ${live.statusCode()}: ${live.body()}" }
        if (live.body().contains(sha1)) {
            logger.lifecycle("Modrinth icon already $sha1 - nothing to upload.")
            return@doLast
        }

        val sent = http.send(
            HttpRequest.newBuilder(URI.create("$base/icon?ext=png"))
                .header("Authorization", key).header("User-Agent", agent)
                .header("Content-Type", "image/png")
                .method("PATCH", HttpRequest.BodyPublishers.ofByteArray(png)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        check(sent.statusCode() == 204) { "Modrinth icon PATCH -> ${sent.statusCode()}: ${sent.body()}" }
        logger.lifecycle("Modrinth icon updated to $sha1.")
    }
}

// The icon is project-level and identical for every node, so the SHA1 check above means only the
// first node of a matrix release uploads. A finalizer rather than a dependency: the jar reaches
// players first, and a failed icon upload cannot hold it back.
tasks.named("publishMods") { finalizedBy(syncModrinthIcon) }
