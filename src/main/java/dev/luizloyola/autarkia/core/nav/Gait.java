package dev.luizloyola.autarkia.core.nav;

/**
 * The pace a brain task hands the follower with a destination. Advisory only: the follower
 * decides where each gait applies. Terrain overrides it both ways — careful ground (cliff rims,
 * narrow landings) slows even a SPRINT to a crawl, a leap's run-up takes full speed mid-STROLL.
 */
public enum Gait {
    /**
     * An unhurried amble (~55% walk speed) for pastime rather than errand — wandering. At full
     * walk speed a Person reads as perpetually late.
     */
    STROLL,
    /** The default: a player's exact walking pace. */
    WALK,
    /** Sprint wherever the terrain safely allows — the flee gait. Sprint meters bank exhaustion. */
    SPRINT
}
