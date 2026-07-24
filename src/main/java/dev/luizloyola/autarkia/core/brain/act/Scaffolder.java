package dev.luizloyola.autarkia.core.brain.act;

/**
 * The climb actuator port: one nerd-pole step per {@link #up} call — jump, place a carried block
 * into the cell just vacated, land on it — the body owning the jump/place timing as the breaker
 * owns crack timing. Coming down needs no port: the pillar is ordinary blocks, broken underfoot
 * with the {@link BlockBreaker} and reclaimed by walk-over pickup (decision: Luiz — a lumberjack
 * pillars on her own logs).
 *
 * <p>Lifecycle as the siblings: after a successful {@link #up}, {@link #state()} reports RISING
 * until the step lands (RISEN) or dies (FAILED); the next {@link #up} or {@link #abort} clears it.
 */
public interface Scaffolder {
    /**
     * Begin one pillar step using one {@code itemId} block from the carried inventory.
     * Returns {@code false} when the step cannot start — nothing carried, no headroom, or a
     * step already in flight — in which case nothing was consumed.
     */
    boolean up(String itemId);

    /** Progress of the most recent step; IDLE when none. */
    ScaffoldState state();

    /** Abandon the step in flight (mid-air placement doesn't happen). Idempotent. */
    void abort();
}
