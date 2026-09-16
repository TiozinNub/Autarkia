pluginManagement {
    repositories {
        mavenLocal()
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.fabricmc.net/")
        maven("https://maven.kikugie.dev/releases") { name = "KikuGie Releases" }
        maven("https://maven.kikugie.dev/snapshots") { name = "KikuGie Snapshots" }
    }
}

plugins {
    id("dev.kikugie.stonecutter") version "0.9.6"

    // Cross-compat between 26.1+ (unobfuscated) and older versions (https://codeberg.org/KikuGie/loom-back-compat)
    id("dev.kikugie.loom-back-compat") version "0.4"

    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// The Minecraft targets this mod builds against.
//
// ⚠ THESE MUST STAY IN STEP WITH ANIMA'S. Until 2026-08-16 one `targets` list in one repo made
// that structural; now it is a promise across repositories, and the same goes for the per-node
// `deps.fabric_api` pins in stonecutter.properties.toml. Worse here than in the library: this mod
// resolves `anima-<mcversion>` from Maven, so a target Anima has not published is a build that
// fails outright, and a target where the two disagree about Fabric API is a pair that loads
// happily and misbehaves in somebody's game.
//
// 1.20.1 and 1.21.1 are TEMPORARILY DROPPED: the Person entity is built on the 1.21.9+ player
// Avatar + retained-mode render pipeline (Avatar/ClientAvatarEntity/AvatarRenderState/
// SubmitNodeCollector), none of which exist on those targets — and 1.20.1 also predates the
// data-component system. This is the mod the constraint belongs to; Anima drops them only to keep
// the node sets identical. Re-add once Person stops extending Avatar.
val targets = listOf(
    "1.21.11" to "1.21.11",
    "26.1.x" to "26.1.2",
    "26.2.x" to "26.2",
    "26.3.x" to "26.3",
)

// The snapshot node: a Minecraft version that is not a target yet, usually Mojang's newest
// build. It exists only while the file scripts/snapshot.sh writes does, and git ignores that
// file, so the committed node set never changes. Its version is what Fabric Loader itself calls
// the build — 26.4-snapshot-3 is 26.4-alpha.3 — so it sorts after every 26.3 and before 26.4,
// and a change first seen in a snapshot is gated `//? if >=26.4-alpha`: 26.4 and the road to it.
// The id the game and Maven know travels in the file as `mod.minecraft`.
val snapshotSemver: String? = file("versions/snapshot/stonecutter.properties.toml")
    .takeIf { it.exists() }
    ?.let { f ->
        Regex("""^snapshot\.semver\s*=\s*"([^"]+)"""", RegexOption.MULTILINE)
            .find(f.readText())?.groupValues?.get(1)
            ?: error("${f.path} has no snapshot.semver — rewrite it with scripts/snapshot.sh, "
                    + "or remove it with scripts/snapshot.sh off")
    }

stonecutter {
    create(rootProject) {
        // A ROOT branch: one mod, one source root at `src/`, node paths of `:<version>` rather
        // than the `:<mod>:<version>` this used while it shared a tree with Anima.
        targets.forEach { (proj, ver) -> version(proj, ver) }
        snapshotSemver?.let { version("snapshot", it) }

        // Primary dev target: 26.1.x (Sinytra Connector's primary supported line)
        vcsVersion = "26.1.x"
    }
}

rootProject.name = "Autarkia"
