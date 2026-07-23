package dev.luizloyola.autarkia.mod.command;

import dev.luizloyola.autarkia.core.person.PersonId;
import dev.luizloyola.autarkia.mod.net.DebugGlowPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The transient "who am I commanding" pin behind {@code /autarkia select}.
 *
 * <p>Every person-scoped subcommand used to target the {@code Person} nearest the command source
 * (a 32-block radius). That is unusable when several Persons cluster, when one is across the map,
 * or on the headless dev server, where the console sits at world origin. A pin fixes that: select a
 * Person once and the terse commands keep flowing to it.
 *
 * <p>Selection is a dev/admin ergonomic, not simulation state, so it lives here in memory and dies
 * with the server process — never in the world save. Each command source gets its own slot: a player
 * is keyed by their UUID; every non-player source (the console, rcon, a command block) shares one
 * {@link #CONSOLE} slot. That single shared console slot is what lets the headless FIFO test loop
 * select once and then fire a run of commands at the same Person.
 *
 * <p>A pin holds only a {@link PersonId} — the stable, entity-independent handle. Turning it back
 * into a live entity, and failing loudly when that entity is gone, is the resolver's job in
 * {@link AutarkiaCommands}: a stale pin must never silently fall through to whoever is nearest, which
 * would quietly command the wrong Person.
 *
 * <p>A <em>player's</em> pin is the same slot the debug wand writes to, and every change to it is
 * mirrored to that player's client (via {@link DebugGlowPayload}) so the client can glow the
 * selected Person. That mirroring is centralised here — the single choke point through which a pin
 * change reaches the client — so no caller can forget it. The console slot is never synced (it has
 * no client). A fresh client (login/respawn) is re-primed via {@link #resync}.
 */
public final class PersonSelection {
    private PersonSelection() {}

    /** The shared slot for every non-player source (console / rcon / command block). */
    private static final Object CONSOLE = new Object();

    private static final Map<Object, PersonId> PINS = new ConcurrentHashMap<>();

    private static Object key(CommandSourceStack source) {
        return source.getEntity() instanceof ServerPlayer player ? player.getUUID() : CONSOLE;
    }

    /** Pins {@code id} to this source, replacing any previous pin. */
    public static void pin(CommandSourceStack source, PersonId id) {
        PINS.put(key(source), id);
        if (source.getEntity() instanceof ServerPlayer player) sync(player, id);
    }

    /** Pins {@code id} to {@code player}'s slot — the debug wand's entry point, keyed by UUID exactly
     *  like this player's {@code /autarkia select}, so the wand and the command share one selection. */
    public static void pin(ServerPlayer player, PersonId id) {
        PINS.put(player.getUUID(), id);
        sync(player, id);
    }

    /** Drops this source's pin; returns whether one was actually present. */
    public static boolean clear(CommandSourceStack source) {
        boolean had = PINS.remove(key(source)) != null;
        if (source.getEntity() instanceof ServerPlayer player) sync(player, null);
        return had;
    }

    public static Optional<PersonId> pinned(CommandSourceStack source) {
        return Optional.ofNullable(PINS.get(key(source)));
    }

    /** This player's current pin, if any — the wand's read path (same slot as
     *  {@link #pinned(CommandSourceStack)} for a player source). */
    public static Optional<PersonId> pinned(ServerPlayer player) {
        return Optional.ofNullable(PINS.get(player.getUUID()));
    }

    /** Re-sends this player's current pin (or "none") to their client — used when the entity is
     *  (re)created (login, respawn) and the client shadow is empty. */
    public static void resync(ServerPlayer player) {
        sync(player, PINS.get(player.getUUID()));
    }

    /** Mirrors a player's pin to their client for the selection glow. Skips clients that did not
     *  register the channel (vanilla clients, the headless test loop) — nothing to glow there. */
    private static void sync(ServerPlayer player, @Nullable PersonId id) {
        if (ServerPlayNetworking.canSend(player, DebugGlowPayload.TYPE)) {
            ServerPlayNetworking.send(player, DebugGlowPayload.of(id));
        }
    }
}
