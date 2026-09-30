package dev.luizloyola.autarkia.core.direction;

import dev.luizloyola.anima.core.brain.knowledge.Region;
import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.Objects;

/**
 * A party's claimed plot, and the yard its goods go to.
 *
 * @param cleared whether a clearing of this plot has finished — all the party can know about the
 *                trees on it, since nobody shares what they merely walked past
 */
public record Home(Region plot, Pos yard, boolean cleared) {

    public Home {
        Objects.requireNonNull(plot, "plot");
        Objects.requireNonNull(yard, "yard");
    }

    /**
     * How far a plot reaches below and above its yard. A plot is ground, but its clearing must take
     * in a whole tree standing on it, crown and all, and the ground is rarely flat.
     */
    private static final int BELOW = 16;
    private static final int ABOVE = 48;

    /** A new, uncleared HOME: a square plot {@code radius} out from its yard, which is its centre. */
    public static Home square(Pos yard, int radius) {
        return new Home(new Region(
                new Pos(yard.x() - radius, yard.y() - BELOW, yard.z() - radius),
                new Pos(yard.x() + radius, yard.y() + ABOVE, yard.z() + radius)), yard, false);
    }

    public Home withCleared(boolean now) {
        return new Home(plot, yard, now);
    }
}
