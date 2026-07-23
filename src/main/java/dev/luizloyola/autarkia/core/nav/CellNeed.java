package dev.luizloyola.autarkia.core.nav;

/**
 * One cell a path leg depends on: the live world at {@code (x, y, z)} must still classify as
 * {@link #required()}. {@link PathIntegrity} derives a handful per {@link Waypoint} and the follower
 * re-checks those a few nodes ahead, so terrain edited out from under a plan (a floor mined away, a
 * corridor walled off, a swim lane drained) triggers a re-path, not a stumble on arrival.
 */
public record CellNeed(int x, int y, int z, CellType required) {}
