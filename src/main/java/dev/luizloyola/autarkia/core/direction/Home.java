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

    public Home withCleared(boolean now) {
        return new Home(plot, yard, now);
    }
}
