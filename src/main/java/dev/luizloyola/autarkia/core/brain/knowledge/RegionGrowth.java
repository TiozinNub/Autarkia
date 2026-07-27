package dev.luizloyola.autarkia.core.brain.knowledge;

import dev.luizloyola.autarkia.core.brain.sense.Pos;
import dev.luizloyola.autarkia.core.config.Config;
import dev.luizloyola.autarkia.core.config.Knob;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One in-flight structure scan — an incremental, budgeted BFS. Give it reads via {@link #step}
 * until {@link #isDone()}; an unfinished scan keeps its frontier and resumes next tick, so an oak
 * (~400 reads) completes across a handful of ticks while the person walks. It never touches the
 * store; the sensor decides what a finished {@link GrownRegion} becomes.
 *
 * <p>Connectivity is <b>26-way</b> (see {@link #NEIGHBORS}), because worldgen hangs branches off
 * trunks diagonally and a face-only walk loses them silently. It costs roughly four times the probe
 * reads, and groves touching at a corner fuse into one region — which is why the rule individuates
 * what the growth fused ({@link GrowthRule#evaluate}).
 *
 * <p>Bounded three ways, each marking the result {@code partial}: the block cap, the spread cap
 * (Chebyshev from seed), and unloaded borders ({@link BlockKind#UNKNOWN}).
 */
public final class RegionGrowth {
    /** Block cap on one scan — hitting it marks the region partial. */
    public static int maxBlocks() {
        return Config.get().i(Knob.REGION_MAX_BLOCKS);
    }

    /** Chebyshev spread cap from the seed — what splits a fused mega-forest into groves. */
    public static int maxSpread() {
        return Config.get().i(Knob.REGION_MAX_SPREAD);
    }

    /**
     * The 26 cells touching a cell — faces first, then edges and corners. Diagonals are
     * load-bearing: worldgen attaches branches to a trunk at a CORNER, and a face-only walk does
     * not see them. Measured on a saved fancy oak (2026-07-26): of 28 logs a 6-neighbour fill
     * from the stump reached 24, and the 4 it missed are the 4 the chopper left standing.
     *
     * <p>Faces first so that a scan out of budget or at {@link #maxBlocks()} has collected as
     * close as possible to the old face-first shape.
     */
    private static final int[][] NEIGHBORS = touchingCells();

    private static int[][] touchingCells() {
        int[][] faces = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        int[][] all = new int[26][];
        System.arraycopy(faces, 0, all, 0, faces.length);
        int i = faces.length;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    int touched = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
                    if (touched > 1) { // 0 is the cell itself, 1 is a face — already listed
                        all[i++] = new int[] {dx, dy, dz};
                    }
                }
            }
        }
        return all;
    }

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
        // Hoisted so one step is always bounded by one consistent pair of caps, even if a
        // reload lands mid-scan.
        int spreadCap = maxSpread();
        int blockCap = maxBlocks();
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
                if (chebyshev(n, seed) > spreadCap) {
                    partial = true;
                    continue;
                }
                if (blocks.size() >= blockCap) {
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
        List<GrownRegion.Part> parts = new ArrayList<>();
        for (GrowthRule.Evaluation eval : rule.evaluate(blocks, seed, probe)) {
            parts.add(new GrownRegion.Part(eval.anchor(), boundsOf(eval.blocks().keySet()),
                    eval.units(), Collections.unmodifiableMap(eval.blocks())));
        }
        this.result = new GrownRegion(rule.kind(), partial, Collections.unmodifiableMap(blocks),
                List.copyOf(parts));
    }

    /** The smallest box holding every cell — a part's "where", folded once at the end. */
    private static Region boundsOf(Iterable<Pos> cells) {
        Region folded = null;
        for (Pos p : cells) {
            folded = folded == null ? Region.of(p) : folded.including(p);
        }
        return folded;
    }

    private static int chebyshev(Pos a, Pos b) {
        int dx = Math.abs(a.x() - b.x());
        int dy = Math.abs(a.y() - b.y());
        int dz = Math.abs(a.z() - b.z());
        return Math.max(dx, Math.max(dy, dz));
    }
}
