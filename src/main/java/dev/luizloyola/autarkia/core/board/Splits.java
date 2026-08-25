package dev.luizloyola.autarkia.core.board;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The registry of {@link Split}s, by {@link Split#id()} — how a saved project finds the policy it
 * was posted with again.
 *
 * <p>Canonical per id, in registration order, like the other extension points here
 * ({@code Clearings}, {@code PartyProjects}, {@code PoiKind}, {@code Producers}, {@code Being.Kind}):
 * registered once at bootstrap beside the thing registered, and the instance handed back is the one
 * the store and the project both mean.
 */
public final class Splits {

    private static final Map<String, Split> BY_ID = new LinkedHashMap<>();

    private Splits() {
    }

    /** Registers a split, replacing any earlier one with the same id. */
    public static Split register(Split split) {
        BY_ID.put(split.id(), split);
        return split;
    }

    /** The split with this id, or empty when no build here supplies one. */
    public static Optional<Split> byId(String id) {
        return Optional.ofNullable(BY_ID.get(id));
    }

    /** Every registered split, in registration order. */
    public static Collection<Split> all() {
        return List.copyOf(BY_ID.values());
    }

    /** Drops every registration. Tests only — a mod's bootstrap registers once and never unwinds. */
    public static void clear() {
        BY_ID.clear();
    }
}
