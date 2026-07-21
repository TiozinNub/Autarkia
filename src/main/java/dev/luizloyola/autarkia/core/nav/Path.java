package dev.luizloyola.autarkia.core.nav;

import java.util.List;

/**
 * The pathfinder's answer: the waypoints to walk, in order, <em>excluding</em> the start cell. A
 * path may be partial — an unreachable goal or a spent budget returns the route to the reachable
 * cell closest to it rather than nothing, so an agent still makes visible progress, like vanilla.
 *
 * @param waypoints   steps to take, in order; empty when start == goal or nothing was reachable
 * @param reachedGoal whether the last waypoint is the requested goal cell
 */
public record Path(List<Waypoint> waypoints, boolean reachedGoal) {
    public Path {
        waypoints = List.copyOf(waypoints);
    }

    public boolean isEmpty() {
        return this.waypoints.isEmpty();
    }

    public Waypoint last() {
        return this.waypoints.get(this.waypoints.size() - 1);
    }
}
