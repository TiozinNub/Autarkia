package dev.luizloyola.autarkia.core.board;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The registry of {@link Clearing}s, by {@link Clearing#id()} — how a saved project finds the
 * behaviour it was posted with again.
 *
 * <p>Canonical per id, in registration order, like the other extension points here
 * ({@code PoiKind}, {@code Producers}, {@code Being.Kind}): registered once at bootstrap beside the
 * thing registered, and the instance handed back is the one the store, the command and the project
 * all mean.
 *
 * <p>An unknown id on load is a real failure the store reports (see {@code PartyBoardData}):
 * dropping it quietly would clear the party's work board with nothing said.
 */
public final class Clearings {

    private static final Map<String, Clearing> BY_ID = new LinkedHashMap<>();

    private Clearings() {
    }

    /** Registers a clearing, replacing any earlier one with the same id. */
    public static Clearing register(Clearing clearing) {
        BY_ID.put(clearing.id(), clearing);
        return clearing;
    }

    /** The clearing with this id, or empty when no build here supplies one. */
    public static Optional<Clearing> byId(String id) {
        return Optional.ofNullable(BY_ID.get(id));
    }

    /** Every registered clearing, in registration order — what {@code post clear} completes on. */
    public static Collection<Clearing> all() {
        return java.util.List.copyOf(BY_ID.values());
    }

    /** Drops every registration. Tests only — a mod's bootstrap registers once and never unwinds. */
    public static void clear() {
        BY_ID.clear();
    }
}
