plugins {
    // This plugin applies the correct loom variant based on the Minecraft version
    id("dev.kikugie.loom-back-compat")
    id("me.modmuss50.mod-publish-plugin") version "2.1.1"
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
}

tasks.named<Test>("test") {
    useJUnitPlatform()
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
        jvmArguments.add("-Dmixin.debug.export=true") // Exports transformed classes for debugging

        // Hot swap (-Photswap, or scripts/{client,server}.sh --hotswap): open a JDWP port so
        // scripts/hotswap.sh can push recompiled classes into the RUNNING game, and turn on
        // enhanced class redefinition so a swap may add methods and fields — stock HotSpot
        // allows method bodies only. Client and server get their own port so both can be
        // swapped at once. suspend=n: the game boots without waiting for anyone to attach.
        if (providers.gradleProperty("hotswap").isPresent) {
            jvmArguments.add("-XX:+AllowEnhancedClassRedefinition")
            jvmArguments.add("-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:${if (name == "client") 5006 else 5005}")
        }

        // Real Microsoft-account login via DevAuth Neo: scripts/client.sh --auth
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