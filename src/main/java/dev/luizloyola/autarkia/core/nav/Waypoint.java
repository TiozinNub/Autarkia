package dev.luizloyola.autarkia.core.nav;

/**
 * One step of a computed {@link Path}: the cell the agent's feet stand in after the step, and the
 * {@link MoveType move} that gets it there from the previous waypoint (or from the start position,
 * for the first waypoint).
 */
public record Waypoint(int x, int y, int z, MoveType move) {}
