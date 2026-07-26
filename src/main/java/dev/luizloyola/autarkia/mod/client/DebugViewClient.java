package dev.luizloyola.autarkia.mod.client;

import dev.luizloyola.autarkia.mod.net.DebugViewPayload;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import org.jspecify.annotations.Nullable;

/**
 * Client-side shadow of the latest {@link DebugViewPayload} — the one frame of server truth
 * {@link DebugViewRenderer} redraws until the next arrives. Read-only and non-authoritative like
 * {@link DebugGlowClient}; the server owns what is switched on.
 *
 * <p>{@code volatile} because the payload receiver writes it on the client thread and the RENDER
 * thread reads it: the reference is swapped wholesale and its contents are immutable, so a frame
 * never sees a half-torn mix.
 */
@Environment(EnvType.CLIENT)
public final class DebugViewClient {
    private DebugViewClient() {}

    private static volatile @Nullable DebugViewPayload latest;

    public static void install() {
        ClientPlayNetworking.registerGlobalReceiver(DebugViewPayload.TYPE,
                (payload, context) -> latest = payload.isEmpty() ? null : payload);
        // Drop the snapshot on disconnect so a stale frame can't flash on the next world.
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> latest = null);
    }

    /** The snapshot to draw, or {@code null} when nothing is switched on. */
    public static @Nullable DebugViewPayload get() {
        return latest;
    }
}
