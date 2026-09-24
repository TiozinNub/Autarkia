package dev.luizloyola.autarkia.core.direction;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The registry of {@link DirectionLine}s by id — {@code PartyProjects}' pattern. */
public final class Lines {

    private static final Map<String, DirectionLine> BY_ID = new LinkedHashMap<>();

    private Lines() {
    }

    public static DirectionLine register(DirectionLine line) {
        BY_ID.put(line.id(), line);
        return line;
    }

    public static Optional<DirectionLine> byId(String id) {
        return Optional.ofNullable(BY_ID.get(id));
    }

    public static Collection<DirectionLine> all() {
        return List.copyOf(BY_ID.values());
    }

    /** Drops every registration. Tests only. */
    public static void clear() {
        BY_ID.clear();
    }
}
