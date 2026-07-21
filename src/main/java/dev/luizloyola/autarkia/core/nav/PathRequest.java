package dev.luizloyola.autarkia.core.nav;

/**
 * One pathfinding question: from the start cell (where the agent's feet are) to the goal cell,
 * for an agent with the given {@link AgentProfile capabilities}, expanding at most
 * {@code maxNodes} cells before settling for a partial path.
 */
public record PathRequest(
        int startX, int startY, int startZ,
        int goalX, int goalY, int goalZ,
        AgentProfile profile, int maxNodes) {

    /**
     * Default search budget. At ~8 neighbour probes per expansion this bounds worst-case work per
     * request to a few tens of thousands of grid reads (milliseconds) while comfortably covering
     * any route inside the snapshot boxes v1 uses.
     */
    public static final int DEFAULT_MAX_NODES = 4096;

    public PathRequest {
        if (maxNodes < 1) throw new IllegalArgumentException("maxNodes must be >= 1: " + maxNodes);
    }

    public static PathRequest of(int startX, int startY, int startZ,
                                 int goalX, int goalY, int goalZ, AgentProfile profile) {
        return new PathRequest(startX, startY, startZ, goalX, goalY, goalZ, profile, DEFAULT_MAX_NODES);
    }
}
