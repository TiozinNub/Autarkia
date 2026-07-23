package dev.luizloyola.autarkia.core.nav;

/**
 * How a waypoint is entered from the one before — what the follower presses. {@link #JUMP}: the
 * jump input. {@link #DROP}: nothing, walk off the edge. {@link #WALK} (cardinal, diagonal and
 * stride alike): plain forward. {@link #LEAP}: a jump at the edge, with a sprint run-up when the
 * gap is 2+ wide. {@link #SWIM}: forward plus held jump for buoyancy, the feet ending in a water
 * cell rather than on ground; climbing back out is an ordinary {@link #WALK}/{@link #JUMP}, known
 * as the exit because the body is still in the water. Dive/surface would add their own values.
 */
public enum MoveType {
    WALK,
    JUMP,
    DROP,
    LEAP,
    SWIM
}
