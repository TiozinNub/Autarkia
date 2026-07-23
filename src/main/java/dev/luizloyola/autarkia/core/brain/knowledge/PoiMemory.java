package dev.luizloyola.autarkia.core.brain.knowledge;

import dev.luizloyola.autarkia.core.brain.sense.Pos;

/**
 * One remembered point of interest — what a Person <em>believes</em> is out there, not what is:
 * enough to path to it ({@link #anchor}), re-walk it ({@link #bounds}) and price it
 * ({@link #units}, {@link #lastSeenTick}). A task re-reads the actual blocks, so a wrong memory
 * produces a failed step and an update, never a crash.
 *
 * @param kind         what she believe this is
 * @param anchor       the cell to walk to — a grove's lowest trunk log, water's shore cell
 * @param bounds       the inclusive box the region occupied when last seen
 * @param units        kind-specific size: logs in a grove, surface cells of a water body —
 *                     what "how much is there" means for method costing
 * @param partial      true when the scan hit its growth cap: there is AT LEAST this much
 *                     (a mega-forest reads as several partial groves)
 * @param lastSeenTick game time when last noticed or refreshed — staleness raises a method's
 *                     estimated cost, per the brain design
 */
public record PoiMemory(PoiKind kind, Pos anchor, Region bounds, int units, boolean partial,
                        long lastSeenTick) {
    public PoiMemory {
        if (units < 0) {
            throw new IllegalArgumentException("units < 0: " + units);
        }
        if (!bounds.contains(anchor)) {
            throw new IllegalArgumentException("anchor " + anchor + " outside bounds " + bounds);
        }
    }

    /** The same belief, re-confirmed now — what a re-sighting writes back. */
    public PoiMemory seenAt(long now) {
        return new PoiMemory(kind, anchor, bounds, units, partial, now);
    }

    /** Ticks since last confirmed, given the current game time — the staleness input. */
    public long age(long now) {
        return now - lastSeenTick;
    }
}
