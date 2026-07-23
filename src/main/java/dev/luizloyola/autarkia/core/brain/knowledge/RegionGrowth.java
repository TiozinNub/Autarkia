package dev.luizloyola.autarkia.core.brain.knowledge;

import dev.luizloyola.autarkia.core.brain.sense.Pos;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * One in-flight structure scan: an incremental, budgeted BFS. An unfinished scan keeps its
 * frontier and resumes next tick, so an oak (~400 reads) completes across a handful of ticks
 * while the person keeps walking. It only collects; the sensor decides what a finished
 * {@link GrownRegion} becomes.
 *
 * <p>Bounded three ways, each marking the result {@code partial}: the block cap, the spread cap
 * (Chebyshev from seed — a fused mega-forest becomes several partial groves), and unloaded
 * borders ({@link BlockKind#UNKNOWN}).
 */
public final class RegionGrowth {
    public static final int MAX_BLOCKS = 512;
    public static final int MAX_SPREAD = 24;

    private static final int[][] NEIGHBORS =
            {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    private final GrowthRule rule;
    private final Pos seed;
    private final Map<Pos, BlockKind> blocks = new LinkedHashMap<>();
    private final Set<Pos> seen = new HashSet<>();
    private final Deque<Pos> frontier = new ArrayDeque<>();
    private boolean partial;
    private GrownRegion result;

    public RegionGrowth(GrowthRule rule, Pos seed, BlockKind seedKind) {
        this.rule = rule;
        this.seed = seed;
        this.blocks.put(seed, seedKind);
        this.seen.add(seed);
        this.frontier.add(seed);
    }

    /**
     * Advances the scan by at most {@code maxReads} probe reads; returns the reads actually
     * spent. Call again next tick while {@link #isDone()} is false.
     */
    public int step(BlockProbe probe, int maxReads) {
        int reads = 0;
        while (result == null && reads < maxReads) {
            if (frontier.isEmpty()) {
                finish(probe);
                break;
            }
            Pos p = frontier.pollFirst();
            boolean outOfBudget = false;
            for (int[] d : NEIGHBORS) {
                Pos n = new Pos(p.x() + d[0], p.y() + d[1], p.z() + d[2]);
                if (seen.contains(n)) {
                    continue;
                }
                if (reads >= maxReads) {
                    // Requeue p at the front: its remaining neighbors get their turn next step;
                    // the seen-set makes re-visiting the checked ones free.
                    frontier.addFirst(p);
                    outOfBudget = true;
                    break;
                }
                seen.add(n);
                BlockKind kind = probe.at(n.x(), n.y(), n.z());
                reads++;
                if (kind == BlockKind.UNKNOWN) {
                    partial = true;
                    continue;
                }
                if (!rule.joins(n, kind, probe)) {
                    continue;
                }
                if (chebyshev(n, seed) > MAX_SPREAD) {
                    partial = true;
                    continue;
                }
                if (blocks.size() >= MAX_BLOCKS) {
                    partial = true;
                    frontier.clear();
                    break;
                }
                blocks.put(n, kind);
                frontier.addLast(n);
            }
            if (outOfBudget) {
                break;
            }
        }
        return reads;
    }

    public boolean isDone() {
        return result != null;
    }

    /** The finished scan; only valid once {@link #isDone()}. */
    public GrownRegion result() {
        if (result == null) {
            throw new IllegalStateException("growth still running");
        }
        return result;
    }

    private void finish(BlockProbe probe) {
        Region folded = null;
        for (Pos p : blocks.keySet()) {
            folded = folded == null ? Region.of(p) : folded.including(p);
        }
        Region bounds = folded;
        Optional<GrowthRule.Evaluation> eval = rule.evaluate(blocks, seed, probe);
        Map<Pos, BlockKind> payload = Collections.unmodifiableMap(blocks);
        this.result = eval
                .map(e -> new GrownRegion(rule.kind(), true, e.anchor(), bounds, e.units(), partial, payload))
                .orElseGet(() -> new GrownRegion(rule.kind(), false, null, bounds, 0, partial, payload));
    }

    private static int chebyshev(Pos a, Pos b) {
        int dx = Math.abs(a.x() - b.x());
        int dy = Math.abs(a.y() - b.y());
        int dz = Math.abs(a.z() - b.z());
        return Math.max(dx, Math.max(dy, dz));
    }
}
