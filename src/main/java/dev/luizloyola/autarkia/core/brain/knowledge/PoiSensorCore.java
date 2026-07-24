package dev.luizloyola.autarkia.core.brain.knowledge;

import dev.luizloyola.autarkia.core.brain.sense.Pos;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The "notice as you go" pipeline: one per person, pure core; the mod-layer {@code PoiSensor}
 * hands it the person's feet, the game time and a live {@link BlockProbe} each tick. Crescent →
 * pending queue → probe → (confirm-ray → growth) → store + claims, all inside one per-tick read
 * wallet, so the cost ceiling is constant.
 *
 * <p>Per probed column the claims answer first (the O(1) fast path):
 * <ul>
 *   <li>a claim <em>above</em> the current surface — that block is gone, the heightmap having no
 *       other way to say so: the region's belief is invalidated;</li>
 *   <li>surface claimed and matching — refreshed; mismatching — invalidated (a negative claim is
 *       just cleared);</li>
 *   <li>claims only <em>below</em> the surface — benign (region interior), skip;</li>
 *   <li>no claims — hypothesis: leaves/log grow a {@link TreeRule} scan, water a
 *       {@link WaterRule} one, gated by the confirm-ray ({@code visibleFromEyes}). One growth
 *       runs at a time.</li>
 * </ul>
 */
public final class PoiSensorCore {
    /**
     * The per-tick read wallet — the sensor's whole cost ceiling. Walking feeds ~5 columns/tick
     * (2 reads each), so probing normally keeps up in real time and the rest of the wallet
     * drains growths. Tuning knob.
     */
    public static final int READS_PER_TICK = 64;
    /**
     * Pending-column capacity, sized to hold a FULL disc (~450 columns at R=12): a spawn or
     * teleport glance that drops columns biases the initial sweep toward whichever side
     * enumerates last. Overflow drops the oldest.
     */
    public static final int QUEUE_CAP = 512;
    /** Flat wallet charge for one confirm-ray (a ~R-block voxel walk). Tuning knob. */
    public static final int RAY_COST = 8;

    private final PersonKnowledge knowledge;
    private final ClaimIndex claims = new ClaimIndex();
    private final CrescentSampler sampler = new CrescentSampler();
    private final Deque<Column> pending = new ArrayDeque<>();
    private RegionGrowth active;
    /** The surface cell that seeded {@link #active} — reported on a DISMISSED outcome. */
    private Pos activeSeed;

    public PoiSensorCore(PersonKnowledge knowledge) {
        this.knowledge = knowledge;
    }

    /** One perception tick — the beliefs noted or forgotten, empty on the common quiet tick. */
    public List<SenseEvent> tick(Pos feet, long now, BlockProbe probe) {
        for (Column column : sampler.advance(feet)) {
            if (pending.size() >= QUEUE_CAP) {
                pending.pollFirst();
            }
            pending.addLast(column);
        }
        List<SenseEvent> events = new ArrayList<>();
        int reads = 0;
        while (reads < READS_PER_TICK) {
            if (active != null) {
                reads += active.step(probe, READS_PER_TICK - reads);
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
        if (highestClaim != null) {
            if (highestClaim.y() > top) {
                invalidate(highestClaim, claims.get(highestClaim), events);
                return reads;
            }
            Pos surface = new Pos(column.x(), top, column.z());
            ClaimIndex.Claim exact = claims.get(surface);
            if (exact == null) {
                return reads;
            }
            BlockKind kind = probe.at(surface.x(), surface.y(), surface.z());
            reads++;
            if (kind == exact.expected()) {
                if (exact.anchor() != null
                        && !knowledge.refresh(exact.kind(), exact.anchor(), now)) {
                    // Orphaned claims: the memory is gone (a task forgot it, or eviction) but
                    // the claim survived and would mask the region forever. Drop them so a later
                    // sweep re-discovers whatever stands here now.
                    claims.dropRegion(exact.kind(), exact.anchor());
                }
            } else {
                invalidate(surface, exact, events);
            }
            return reads;
        }
        Pos surface = new Pos(column.x(), top, column.z());
        BlockKind kind = probe.at(surface.x(), surface.y(), surface.z());
        reads++;
        GrowthRule rule = ruleFor(kind);
        if (rule == null) {
            return reads;
        }
        reads += RAY_COST;
        if (!probe.visibleFromEyes(surface)) {
            events.add(SenseEvent.overlooked(rule.kind(), surface));
            return reads;
        }
        active = new RegionGrowth(rule, surface, kind);
        activeSeed = surface;
        return reads;
    }

    private void finish(GrownRegion region, long now, List<SenseEvent> events) {
        if (region.accepted()) {
            PoiMemory memory = knowledge.note(region.toMemory(now));
            claims.claimRegion(region.kind(), memory.anchor(), region.blocks());
            events.add(SenseEvent.noted(memory));
        } else {
            claims.claimNegative(region.kind(), region.blocks());
            events.add(SenseEvent.dismissed(region.kind(), activeSeed));
        }
    }

    private void invalidate(Pos at, ClaimIndex.Claim claim, List<SenseEvent> events) {
        if (claim.anchor() == null) {
            claims.remove(at);
            return;
        }
        if (knowledge.forget(claim.kind(), claim.anchor())) {
            events.add(SenseEvent.forgot(claim.kind(), claim.anchor()));
        }
        claims.dropRegion(claim.kind(), claim.anchor());
    }

    private static GrowthRule ruleFor(BlockKind kind) {
        return switch (kind) {
            case LOG, LEAVES -> TreeRule.INSTANCE;
            case WATER -> WaterRule.INSTANCE;
            default -> null;
        };
    }
}
