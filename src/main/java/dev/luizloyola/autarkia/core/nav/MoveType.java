package dev.luizloyola.autarkia.core.nav;

/**
 * How a waypoint is entered from the one before it. The follower cares: a {@link #JUMP} needs the
 * jump input pressed, a {@link #DROP} just walks off the edge and lets gravity work, a
 * {@link #WALK} (cardinal, diagonal, or stride — same thing to the driver) is plain forward
 * input, and a {@link #LEAP} is a jump across a gap — pressed at the edge, with a sprint run-up
 * when the gap is 2+ wide.
 */
public enum MoveType {
    WALK,
    JUMP,
    DROP,
    LEAP
}
