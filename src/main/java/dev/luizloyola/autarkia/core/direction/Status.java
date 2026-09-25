package dev.luizloyola.autarkia.core.direction;

import java.util.List;
import java.util.Objects;

/**
 * Where a Direction's condition stands, and how to say so: a lang key and its arguments, since the
 * core layer cannot build a chat component.
 */
public record Status(Reading reading, String langKey, List<Object> args) {

    /** The four answers a condition can give. */
    public enum Reading {
        /** The condition holds: a checkpoint, and nothing to post. */
        MET,
        /** It does not: the line's work goes on the board. */
        UNMET,
        /** The party cannot tell this beat — a store in an unloaded chunk. Nothing changes. */
        UNKNOWN,
        /** The line needs something the party does not have yet — a HOME, a base. It waits. */
        WAITING
    }

    public Status {
        Objects.requireNonNull(reading, "reading");
        Objects.requireNonNull(langKey, "langKey");
        args = List.copyOf(args);
    }

    public static Status of(Reading reading, String langKey, Object... args) {
        return new Status(reading, langKey, List.of(args));
    }

    public static final Status NO_HOME = of(Reading.WAITING, "autarkia.direction.status.no_home");

    /** HOME has no workbench and chest yet, and nothing hauls to a HOME with nowhere to put it. */
    public static final Status NO_BASE = of(Reading.WAITING, "autarkia.direction.status.no_base");

    public static final Status UNREAD = of(Reading.UNKNOWN, "autarkia.direction.status.unread");
}
