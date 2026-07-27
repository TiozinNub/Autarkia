package dev.luizloyola.autarkia.core.brain.knowledge;

import dev.luizloyola.autarkia.core.brain.sense.Pos;
import dev.luizloyola.autarkia.core.config.Config;
import dev.luizloyola.autarkia.core.config.Knob;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The whole "notice as you go" pipeline: one per person, pure core; the mod-layer {@code PoiSensor}
 * hands it the person's feet, the game time and a live {@link BlockProbe} each tick. Crescent →
 * pending queue → probe → (confirm-ray → growth) → store + claims, all inside one per-tick read
 * wallet, so the cost ceiling is constant.
 *
 * <p>Per probed column the claims answer first (the O(1) fast path):
 * <ul>
 *   <li>a claim <em>above</em> the surface — that block is gone: the region's belief is invalidated
 *       and the remains re-discover on later crescents;</li>
 *   <li>the surface cell claimed and matching — refreshed; mismatching — invalidated (a negative
 *       claim is just cleared);</li>
 *   <li>claims only <em>below</em> the surface — something new on an investigated footprint, so a
 *       fresh hypothesis. A live region's interior always sits under a claimed surface; skipping
 *       this arm made any taller rebuild invisible forever (live-caught);</li>
 *   <li>no claims — hypothesis: leaves/log grow a {@link TreeRule} scan, water a {@link WaterRule}
 *       one, gated by the confirm-ray. One growth runs at a time.</li>
 * </ul>
 */
public final class PoiSensorCore {
    /**
     * The per-tick read wallet — the sensor's whole cost ceiling. Walking feeds ~5 columns/tick
     * (2 reads each), so probing normally keeps up in real time and the rest of the wallet
     * drains growths. Tuning knob.
     */
    public static int readsPerTick() {
        return Config.get().i(Knob.READS_PER_TICK);
    }

    /**
     * Pending-column capacity, sized to hold a FULL disc (~450 columns at R=12), not just a
     * crescent: dropping columns after a spawn/teleport glance biases the initial sweep toward
     * whichever side enumerates last. Overflow drops the oldest. Raise it with
     * {@code perception.sense_radius} — at or above the disc area, roughly {@code 3.2 * R * R}.
     */
    public static int queueCap() {
        return Config.get().i(Knob.QUEUE_CAP);
    }
    /** Flat wallet charge for one confirm-ray (a ~R-block voxel walk). Tuning knob. */
    public static final int RAY_COST = 8;
    /**
     * A ray-blocked hypothesis retries after this many ticks — by then they or the occluder have
     * moved, so the geometry differs. Without it a column consumed by one failed ray stays
     * invisible while they remain in sense range: tree-behind-tree blindness. Tuning knob.
     */
    public static final int RAY_RETRY_DELAY_TICKS = 60;
    /** Retries per stay-in-range; leaving and re-entering starts a fresh cycle. Sized so the
     *  window (MAX × DELAY = ~30s) outlives the commonest occluder — the front tree they are
     *  busy felling. Tuning knob. */
    public static final int RAY_RETRY_MAX = 10;

    private final PersonKnowledge knowledge;
    private final ClaimIndex claims = new ClaimIndex();
    private final CrescentSampler sampler = new CrescentSampler();
    private final Deque<Column> pending = new ArrayDeque<>();
    private RegionGrowth active;
    /** The surface cell that seeded {@link #active} — reported on a DISMISSED outcome. */
    private Pos activeSeed;
    /** Ray-blocked columns awaiting another look: when each is due, and its attempt count. */
    private final java.util.Map<Column, long[]> rayRetries = new java.util.HashMap<>();

    public PoiSensorCore(PersonKnowledge knowledge) {
        this.knowledge = knowledge;
    }

    /** One perception tick — the beliefs noted or forgotten, empty on the common quiet tick. */
    public List<SenseEvent> tick(Pos feet, long now, BlockProbe probe) {
        for (Column column : sampler.advance(feet)) {
            rayRetries.remove(column); // freshly re-entered range: a fresh retry cycle
            if (pending.size() >= queueCap()) {
                pending.pollFirst();
            }
            pending.addLast(column);
        }
        // Due retries jump the queue — near, and already half-investigated. A spent entry
        // (attempts at cap) stays PARKED in the map, blocking a fresh cycle until they leave and
        // re-enter range.
        for (var entry : rayRetries.entrySet()) {
            if (entry.getValue()[0] <= now) {
                pending.addFirst(entry.getKey());
                entry.getValue()[0] = Long.MAX_VALUE; // rescheduled only if the ray fails again
            }
        }
        List<SenseEvent> events = new ArrayList<>();
        // One wallet for the whole tick, read once — a reload mid-tick must not let a Person
        // spend twice.
        int wallet = readsPerTick();
        int reads = 0;
        while (reads < wallet) {
            if (active != null) {
                reads += active.step(probe, wallet - reads);
                if (!active.isDone()) {
                    break;
                }
                finish(active.result(), now, events);
                active = null;
                continue;
            }
            if (pending.isEmpty()) {
                break;
            }
            reads += probeColumn(pending.pollFirst(), now, probe, events);
        }
        return events;
    }

    /** The person's transient claims — exposed for the debug command's "how full" line. */
    public int claimCount() {
        return claims.size();
    }

    private int probeColumn(Column column, long now, BlockProbe probe, List<SenseEvent> events) {
        Pos highestClaim = claims.highestIn(column);
        int top = probe.surfaceY(column.x(), column.z());
        int reads = 1;
        if (top == Integer.MIN_VALUE) {
            return reads;
        }
        Pos surface = new Pos(column.x(), top, column.z());
        if (highestClaim != null) {
            if (highestClaim.y() > top) {
                reads += invalidate(highestClaim, claims.get(highestClaim), probe, events);
                return reads;
            }
            ClaimIndex.Claim exact = claims.get(surface);
            if (exact != null) {
                BlockKind kind = probe.at(surface.x(), surface.y(), surface.z());
                reads++;
                if (kind == exact.expected()) {
                    if (exact.anchor() != null
                            && !knowledge.refresh(exact.kind(), exact.anchor(), now)) {
                        // Orphaned claims: the memory is gone (a task forgot it, or eviction)
                        // but the claim survives and would mask the region forever. Drop them so
                        // a later sweep re-discovers whatever stands here now.
                        claims.dropRegion(exact.kind(), exact.anchor());
                    }
                } else {
                    reads += invalidate(surface, exact, probe, events);
                }
                return reads;
            }
            // Claims below, surface UNclaimed: the world grew TALLER on this investigated
            // footprint — fall through to the hypothesis path (see the class doc's third arm).
        }
        BlockKind kind = probe.at(surface.x(), surface.y(), surface.z());
        reads++;
        GrowthRule rule = ruleFor(kind);
        if (rule == null) {
            return reads;
        }
        reads += RAY_COST;
        if (!probe.visibleFromEyes(surface)) {
            events.add(SenseEvent.overlooked(rule.kind(), surface));
            // Book another look: the geometry will differ once they (or the occluder) move.
            long[] retry = rayRetries.computeIfAbsent(column, c -> new long[]{0, 0});
            if (retry[1] < RAY_RETRY_MAX) {
                retry[0] = now + RAY_RETRY_DELAY_TICKS;
                retry[1]++;
            }
            return reads;
        }
        rayRetries.remove(column);
        active = new RegionGrowth(rule, surface, kind);
        activeSeed = surface;
        return reads;
    }

    private void finish(GrownRegion region, long now, List<SenseEvent> events) {
        if (!region.accepted()) {
            claims.claimNegative(region.kind(), region.blocks());
            events.add(SenseEvent.dismissed(region.kind(), activeSeed));
            return;
        }
        java.util.Set<Pos> spoken = new java.util.HashSet<>();
        for (GrownRegion.Part part : region.parts()) {
            PoiMemory memory = knowledge.note(region.toMemory(part, now));
            claims.claimRegion(region.kind(), memory.anchor(), part.blocks());
            spoken.addAll(part.blocks().keySet());
            events.add(SenseEvent.noted(memory));
        }
        if (spoken.size() < region.blocks().size()) {
            java.util.Map<Pos, BlockKind> leftovers = new java.util.LinkedHashMap<>();
            for (var entry : region.blocks().entrySet()) {
                if (!spoken.contains(entry.getKey())) {
                    leftovers.put(entry.getKey(), entry.getValue());
                }
            }
            claims.claimNegative(region.kind(), leftovers);
        }
    }

    /**
     * A claimed cell stopped matching the world. The claims drop either way, but the MEMORY is only
     * wrong when its anchor is gone: a half-felled tree still stands on its stump, the cell its
     * anchor names and the one the chop's partial exit means to come back for. Forgetting it here
     * was the lone-stump factory — the bare stump then failed {@link TreeRule}'s sunlit-leaf test
     * and was negative-claimed, findable by nobody. Costs one probe read.
     */
    private int invalidate(Pos at, ClaimIndex.Claim claim, BlockProbe probe, List<SenseEvent> events) {
        if (claim.anchor() == null) {
            claims.remove(at);
            return 0;
        }
        ClaimIndex.Claim anchorClaim = claims.get(claim.anchor());
        boolean anchorStands = anchorClaim != null
                && probe.at(claim.anchor().x(), claim.anchor().y(), claim.anchor().z())
                        == anchorClaim.expected();
        if (!anchorStands && knowledge.forget(claim.kind(), claim.anchor())) {
            events.add(SenseEvent.forgot(claim.kind(), claim.anchor()));
        }
        claims.dropRegion(claim.kind(), claim.anchor());
        return 1;
    }

    private static GrowthRule ruleFor(BlockKind kind) {
        return switch (kind) {
            case LOG, LEAVES -> TreeRule.INSTANCE;
            case WATER -> WaterRule.INSTANCE;
            default -> null;
        };
    }
}
