package dev.luizloyola.autarkia.core.brain.sense;

import dev.luizloyola.autarkia.core.person.PersonId;

/**
 * A nearby person-shaped someone, as one live sighting — another Person or a live PLAYER,
 * indistinguishable. A player's {@link #id} is minted from the account UUID, so it
 * is as stable across sessions as a Person's own.
 *
 * <p>{@link #activity} is derived from OBSERVABLE body signals only — the swinging arm, the feet
 * actually covering ground — never from another brain's internals. That is what makes the player
 * seamlessness free: players have the same observable body, and no brain to peek into.
 */
public record Peer(PersonId id, String name, Pos pos, double distance, Activity activity) {

    /** What the body is visibly doing, coarsest-first — the working arm outranks the feet. */
    public enum Activity {
        /** Standing around — the approachable state the social layer will look for. */
        IDLE,
        /** Feet covering ground since the last look. */
        MOVING,
        /** The arm is swinging — breaking, placing, fighting; busy either way. */
        WORKING
    }
}
