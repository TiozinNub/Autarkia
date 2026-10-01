package dev.luizloyola.autarkia.core.board;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The registry of {@link Felling}s, by {@link Felling#id()} — how a saved project finds the
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
public final class Fellings {

    private static final Map<String, Felling> BY_ID = new LinkedHashMap<>();

    private Fellings() {
    }

    /** Registers a clearing, replacing any earlier one with the same id. */
    public static Felling register(Felling clearing) {
        BY_ID.put(clearing.id(), clearing);
        return clearing;
    }

    /** The clearing with this id, or empty when no build here supplies one. */
    public static Optional<Felling> byId(String id) {
        return Optional.ofNullable(BY_ID.get(id));
    }

    /** Every registered clearing, in registration order — what {@code post fell} completes on. */
    public static Collection<Felling> all() {
        return java.util.List.copyOf(BY_ID.values());
    }

    /** Drops every registration. Tests only — a mod's bootstrap registers once and never unwinds. */
    public static void clear() {
        BY_ID.clear();
    }
}
