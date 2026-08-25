package dev.luizloyola.autarkia.core.board;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The registry of {@link ProjectType}s, by {@link ProjectType#id()} — how a saved project row finds
 * the behaviour it was posted with again.
 *
 * <p>Canonical per id, in registration order, like the other extension points here
 * ({@code Clearings}, {@code PoiKind}, {@code Producers}, {@code Being.Kind}): registered once at
 * bootstrap beside the thing registered, and the instance handed back is the one the store, the
 * codec and the board all mean.
 *
 * <p>A different registry from {@link Clearings}, one level up: {@code Clearings} registers what to
 * clear; this registers what a project IS.
 */
public final class PartyProjects {

    private static final Map<String, ProjectType> BY_ID = new LinkedHashMap<>();

    private PartyProjects() {
    }

    /** Registers a project type, replacing any earlier one with the same id. */
    public static ProjectType register(ProjectType type) {
        BY_ID.put(type.id(), type);
        return type;
    }

    /** The project type with this id, or empty when no build here supplies one. */
    public static Optional<ProjectType> byId(String id) {
        return Optional.ofNullable(BY_ID.get(id));
    }

    /** Every registered project type, in registration order. */
    public static Collection<ProjectType> all() {
        return List.copyOf(BY_ID.values());
    }

    /** Drops every registration. Tests only — a mod's bootstrap registers once and never unwinds. */
    public static void clear() {
        BY_ID.clear();
    }
}
