package dev.luizloyola.autarkia.mod.command;

import dev.luizloyola.autarkia.core.person.PersonId;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

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
    }

    /** Drops this source's pin; returns whether one was actually present. */
    public static boolean clear(CommandSourceStack source) {
        return PINS.remove(key(source)) != null;
    }

    public static Optional<PersonId> pinned(CommandSourceStack source) {
        return Optional.ofNullable(PINS.get(key(source)));
    }
}
