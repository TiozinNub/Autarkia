package dev.luizloyola.autarkia.compat.client.debug;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * The one version-specific thing about drawing gizmos: Where in the frame to hook.
 * {@code net.minecraft.gizmos.Gizmos} is identical on every live target, so the drawing itself is
 * version-neutral and lives in {@code mod.client.DebugViewRenderer}. Only Fabric's event differs:
 *
 * <ul>
 *   <li>26.1+ (fabric-rendering-v1 23.3.1+): {@code level.LevelRenderEvents.BEFORE_GIZMOS}, just
 *       before {@code LevelRenderer.finalizeGizmoCollection()}.
 *   <li>1.21.11 (fabric-rendering-v1 16.2.10, predating that package):
 *       {@code world.WorldRenderEvents.BEFORE_DEBUG_RENDER}, injected at
 *       {@code DebugRenderer.emitGizmos} — the same point in the pipeline.
 * </ul>
 *
 * <p>Fully-qualified names on purpose: an import would fail to resolve on the version that lacks
 * the class.
 */
@Environment(EnvType.CLIENT)
public final class GizmoFrame {
    private GizmoFrame() {}

    /** Runs {@code draw} once per frame, inside the window where {@code Gizmos} calls are collected. */
    public static void onFrame(Runnable draw) {
        //? if >=26.1 {
        net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents.BEFORE_GIZMOS
                .register(context -> draw.run());
        //?} else {
        /*net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents.BEFORE_DEBUG_RENDER
                .register(context -> draw.run());
        *///?}
    }
}
