package dev.luizloyola.autarkia.core.brain.sense;

import dev.luizloyola.autarkia.core.person.PersonId;

/**
 * A nearby person-shaped someone, as one perception — another Person or a live PLAYER,
 * indistinguishable; a player's {@link #id} is minted from the account UUID, so it
 * is as stable across sessions as a Person's own.
 *
 * <p>{@link #awareness} says which channel produced it: SEEN through the vision cone with line
 * of sight, HEARD making noise inside the hearing bubble, REMEMBERED for the linger window after
 * every channel went dark. A REMEMBERED reading is frozen at its last live values; consumers
 * wanting only live truth filter on awareness.
 *
 * <p>{@link #activity} comes from OBSERVABLE body signals only — the swinging arm, the use-item
 * animation, the pose, the visibly opening chest lid — never from another brain's internals.
 * Station activities are plain INFERENCE: {@code AT_CRAFTING} means "facing a crafting table
 * within reach", and is sometimes wrong the way a human watcher would be. {@link #sneaking} is a
 * manner, not an activity: it combines with anything and is what the hearing and range rules
 * read.
 */
public record Peer(PersonId id, String name, Pos pos, double distance, Activity activity,
                   boolean sneaking, Awareness awareness) {

    /** Which channel produced this perception — the freshness story, live-first. */
    public enum Awareness {
        /** In the vision cone with a clear ray to some body part — the full live reading. */
        SEEN,
        /** Made noise inside the hearing bubble — position live (sound places its source), sight unconfirmed. */
        HEARD,
        /** Every channel dark, linger window still open — the last live reading, frozen. */
        REMEMBERED
    }

    /** What the body is visibly doing — one primary occupation, classified coarsest-first. */
    public enum Activity {
        /** Standing around — the approachable state the social layer looks for. */
        IDLE,
        /** Feet covering ground since the last look. */
        MOVING,
        /** Swinging at the world — breaking blocks, or any unclassified arm work. */
        MINING,
        /** Swinging and recently dealt damage — the same arm, a different story. */
        FIGHTING,
        /** The eat use-animation. */
        EATING,
        /** The drink use-animation. */
        DRINKING,
        /** Shield raised. */
        BLOCKING,
        /** Bow or crossbow drawn. */
        AIMING,
        /** At a visibly open chest — seen near it, CONFIRMED by the container (the lid tells). */
        AT_CHEST,
        /** Facing a crafting table within reach — assumed, and fallible. */
        AT_CRAFTING,
        /** In a bed. */
        SLEEPING
    }
}
