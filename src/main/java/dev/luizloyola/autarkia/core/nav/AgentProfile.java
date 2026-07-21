package dev.luizloyola.autarkia.core.nav;

/**
 * The movement capabilities of the agent a path is computed for, as plain data — the neighbour
 * model is parameterized by this rather than hard-coding a Person, so other agents (animals,
 * later) can reuse the engine.
 *
 * @param height     body height in whole cells; every occupied column needs this much clearance
 * @param jumpHeight how many cells it can jump straight up (v1 supports only 0 or 1)
 * @param maxDrop    how many cells it will willingly fall; anything deeper is a hole to route around
 * @param maxLeap    widest gap (in cells) it can jump across at the same level; gaps of 2+ need a
 *                   sprint run-up, so the engine also demands an aligned approach cell for those
 */
public record AgentProfile(int height, int jumpHeight, int maxDrop, int maxLeap) {
    /**
     * A Person: 2 cells tall (1.8 hitbox), jumps 1 block, accepts drops up to 3 (no fall damage
     * worth fearing), leaps gaps up to 3 wide (1 = walking jump, 2–3 = sprint jump; 3 is the
     * vanilla sprint-jump limit and needs the run-up).
     */
    public static final AgentProfile PERSON = new AgentProfile(2, 1, 3, 3);

    public AgentProfile {
        if (height < 1) throw new IllegalArgumentException("height must be >= 1: " + height);
        if (jumpHeight < 0) throw new IllegalArgumentException("jumpHeight must be >= 0: " + jumpHeight);
        if (maxDrop < 0) throw new IllegalArgumentException("maxDrop must be >= 0: " + maxDrop);
        if (maxLeap < 0) throw new IllegalArgumentException("maxLeap must be >= 0: " + maxLeap);
    }
}
