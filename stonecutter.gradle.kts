plugins {
    id("dev.kikugie.stonecutter")
}

stonecutter active "26.1.x"

// See https://stonecutter.kikugie.dev/wiki/config/params
stonecutter parameters {
    // Read off the NODE rather than the root project — correct in both a root-branch tree like
    // this one and the multi-branch tree this was split out of, where the root had no
    // `mod.version` at all. `AutarkiaMod.VERSION` is the one consumer.
    swaps["mod_version"] = "\"${node.project.property("mod.version")}\";"
    swaps["minecraft"] = "\"${node.metadata.version}\";"
    dependencies["fapi"] = node.project.property("deps.fabric_api") as String

    replacements {
        // ⚠ THE ONE RULE SHARED WITH ANIMA — the only line in this file that has to be kept in
        // step with another repository. Every other rule below is Autarkia's alone, and Anima's
        // (PayloadTypeRegistry, the SavedDataStorage pair) are correspondingly absent rather than
        // missing. Inert today: every live node is >= 1.21.11.
        string(current.parsed >= "1.21.11") {
            replace("ResourceLocation", "Identifier")
        }

        string(current.parsed >= "26.1") {
            // Pre-26.1 jars ship the legacy accessWidener format so older parsers
            // (e.g. Sinytra Connector 2.x's remapper) can read them. Autarkia's alone: it is the
            // only one of the two mods with a `.ct` file.
            replace("accessWidener v2 named", "classTweaker v2 official")
        }

        string(current.parsed < "26.1") {
            // CameraRenderState moved into the `renderer.state.level` subpackage at 26.1; the render
            // pipeline is otherwise identical (same 4-arg submit). Source is written in the 26.1 form,
            // so older nodes get the flatter `renderer.state` package. (1.20.1/1.21.1 predate the
            // submit pipeline entirely and still fail elsewhere until their render slice is written.)
            replace(
                "net.minecraft.client.renderer.state.level.CameraRenderState",
                "net.minecraft.client.renderer.state.CameraRenderState"
            )
        }
    }
}
