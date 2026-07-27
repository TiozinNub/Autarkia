package dev.luizloyola.autarkia.core.brain.knowledge;

import dev.luizloyola.autarkia.core.brain.sense.Pos;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One remembered point of interest — what a Person <em>believes</em> is out there, not what is:
 * enough to path to it ({@link #anchor}), re-walk it at task time ({@link #bounds}) and price it
 * as a method target ({@link #units}, {@link #lastSeenTick}). A task re-reads the actual blocks,
 * so a wrong memory costs a failed step and an update, never a crash.
 *
 * @param kind         what they believe this is
 * @param detail       kind-specific qualifier — the SPECIES for a {@link PoiKind#HERD} ("cow"),
 *                     compared by the store's merge so a cow flock never merges into the sheep
 *                     flock beside it. Empty when nothing needs qualifying.
 * @param individual   the entity UUID for a memory of one specific animal (a loner
 *                     {@link PoiKind#HERD} entry), null for aggregates and block POIs.
 *                     World-unique and save-stable, so a wandering pig DRAGS its memory along
 *                     instead of shedding ghost pigs; consulted only for animals perception
 *                     already delivered, never a world query
 * @param anchor       the cell to walk to — a grove's lowest trunk log, water's shore cell, a
 *                     herd's centroid
 * @param bounds       the inclusive box the region occupied when last seen
 * @param units        kind-specific size: logs in a grove, surface cells of a water body, HEAD
 *                     COUNT of a herd — what "how much is there" means for method costing
 * @param partial      true when the scan hit its growth cap: there is AT LEAST this much
 * @param lastSeenTick game time when last noticed or refreshed — staleness raises a method's
 *                     estimated cost, per the brain design
 */
public record PoiMemory(PoiKind kind, String detail, @Nullable UUID individual, Pos anchor,
                        Region bounds, int units, boolean partial, long lastSeenTick) {
    public PoiMemory {
        if (detail == null) {
            throw new IllegalArgumentException("detail is null (use \"\" for none)");
        }
        if (units < 0) {
            throw new IllegalArgumentException("units < 0: " + units);
        }
        if (!bounds.contains(anchor)) {
            throw new IllegalArgumentException("anchor " + anchor + " outside bounds " + bounds);
        }
    }

    /** The detail-free shape every pre-herd kind uses — TREE and WATER qualify nothing. */
    public PoiMemory(PoiKind kind, Pos anchor, Region bounds, int units, boolean partial,
                     long lastSeenTick) {
        this(kind, "", null, anchor, bounds, units, partial, lastSeenTick);
    }

    /** The aggregate shape — a detail but no individual (herd memories, future kinds). */
    public PoiMemory(PoiKind kind, String detail, Pos anchor, Region bounds, int units,
                     boolean partial, long lastSeenTick) {
        this(kind, detail, null, anchor, bounds, units, partial, lastSeenTick);
    }

    /** The same belief, re-confirmed now — what a re-sighting writes back. */
    public PoiMemory seenAt(long now) {
        return new PoiMemory(kind, detail, individual, anchor, bounds, units, partial, now);
    }

    /** Ticks since last confirmed, given the current game time — the staleness input. */
    public long age(long now) {
        return now - lastSeenTick;
    }
}
