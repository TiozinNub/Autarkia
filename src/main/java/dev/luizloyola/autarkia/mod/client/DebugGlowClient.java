package dev.luizloyola.autarkia.mod.client;

import dev.luizloyola.autarkia.core.person.PersonId;
import dev.luizloyola.autarkia.mod.net.DebugGlowPayload;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import org.jspecify.annotations.Nullable;

/**
 * Client-side read-only shadow of this player's server pin ({@code PersonSelection}), delivered by
 * {@link DebugGlowPayload} and read each tick by {@link DebugGlow}. The server owns the pin and
 * pushes every change.
 */
@Environment(EnvType.CLIENT)
public final class DebugGlowClient {
    private DebugGlowClient() {}

    /** Written on the client thread by the payload receiver, read by the render tick. */
    private static volatile @Nullable PersonId selected;

    public static void install() {
        ClientPlayNetworking.registerGlobalReceiver(DebugGlowPayload.TYPE,
                (payload, context) -> selected = payload.personId());
        // Drop the shadow on disconnect so a stale pin can't flash a glow before the next resync.
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> selected = null);
    }

    /** The Person this client's player has pinned, or {@code null} if none. */
    public static @Nullable PersonId get() {
        return selected;
    }
}
