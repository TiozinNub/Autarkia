package dev.luizloyola.autarkia.core.direction;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One version of a line, as a node file declares it: which line, how much of it, and a priority
 * when the table wants to override the one {@link Tree#priorityOf} works out.
 *
 * @param count    the line's one number — logs to stock, food per member; zero where a line has none
 * @param priority the table's own bid, or null to take the default by what the Direction leads to
 */
public record Direction(DirectionId id, int count, @Nullable Double priority) {

    /** The bid of work that leads toward an age not yet reached. The operator's own default. */
    public static final double TOWARD_CORE = 0.5;

    /** The bid of work that leads only toward side objectives. */
    public static final double TOWARD_SIDE = 0.45;

    /**
     * The bid of work that leads nowhere new — a lapsed condition being put right. Still above a
     * settler's own standing wants (0.35), since it is the settlement's.
     */
    public static final double UPKEEP = 0.4;

    /**
     * The least a station going down bids, whatever its Direction would: a base is what the rest
     * is done from (Luiz, 2026-10-01). Above every other Direction's work, under coming back to a
     * furnace ({@code Tend.PRIORITY}).
     */
    public static final double BUILDING = 0.52;

    public Direction {
        Objects.requireNonNull(id, "id");
    }

    public String line() {
        return id.line();
    }
}
