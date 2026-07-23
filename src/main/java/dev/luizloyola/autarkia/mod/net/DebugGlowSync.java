package dev.luizloyola.autarkia.mod.net;

import dev.luizloyola.autarkia.mod.command.PersonSelection;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

/**
 * Re-pushes a player's pin on login and on respawn/end-return. {@code PersonSelection} sends
 * every pin/clear itself; these are the moments a fresh client shadow would miss a still-live pin
 * (a new {@code ServerPlayer}, same UUID, so the in-memory pin survives). Call {@link #install()}
 * from common mod init — the payload type must be registered on both sides.
 */
public final class DebugGlowSync {
    private DebugGlowSync() {}

    public static void install() {
        PayloadTypeRegistry.clientboundPlay().register(DebugGlowPayload.TYPE, DebugGlowPayload.CODEC);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                PersonSelection.resync(handler.player));
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) ->
                PersonSelection.resync(newPlayer));
    }
}
