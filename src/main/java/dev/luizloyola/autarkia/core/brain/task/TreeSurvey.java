package dev.luizloyola.autarkia.core.brain.task;

import dev.luizloyola.autarkia.core.brain.knowledge.BlockKind;
import dev.luizloyola.autarkia.core.brain.knowledge.BlockProbe;
import dev.luizloyola.autarkia.core.brain.sense.Pos;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Individuates trees inside a scanned grove: worldgen fuses canopies, and a chop fells one tree
 * per errand (decision: Luiz — neighbours are re-discovered as a fresh, smaller grove once the
 * grove memory forgets on completion).
 *
 * <p>A <b>trunk</b> is the log run above a <em>grounded base</em> — a log whose support is real
 * ground (probe says {@code OTHER}) — bases clustered so a 2×2 giant is one trunk of four columns.
 * Every other log is a <b>branch</b> of the nearest trunk. A structure with no grounded base (a
 * floating remnant) surveys as zero trunks and is chopped as branches of a virtual trunk.
 */
public final class TreeSurvey {
    private TreeSurvey() {
    }

    /**
     * One individuated tree: {@code base} is the stump layer (one cell per 1×1 tree, four for a
     * 2×2 giant, empty for a floating remnant), {@code upper} and {@code branches} bottom-up. Base
     * cells double as replant sites, one sapling per stump log (decision: Luiz).
     */
    public record Tree(List<Pos> base, List<Pos> upper, List<Pos> branches) {
        public int logCount() {
            return base.size() + upper.size() + branches.size();
        }
    }

    public static List<Tree> survey(Map<Pos, BlockKind> blocks, BlockProbe probe) {
        Set<Pos> logs = new HashSet<>();
        for (Map.Entry<Pos, BlockKind> entry : blocks.entrySet()) {
            if (entry.getValue() == BlockKind.LOG) {
                logs.add(entry.getKey());
            }
        }
        List<Pos> baseCells = new ArrayList<>();
        for (Pos log : logs) {
            Pos below = new Pos(log.x(), log.y() - 1, log.z());
            if (!logs.contains(below) && probe.at(below.x(), below.y(), below.z()) == BlockKind.OTHER) {
                baseCells.add(log);
            }
        }
        // Chebyshev ≤ 1, so a 2×2 giant clusters as one trunk.
        List<List<Pos>> bases = cluster(baseCells);
        List<Tree> trees = new ArrayList<>();
        Set<Pos> trunkCells = new HashSet<>();
        for (List<Pos> base : bases) {
            List<Pos> upper = new ArrayList<>();
            for (Pos cell : base) {
                trunkCells.add(cell);
                Pos up = new Pos(cell.x(), cell.y() + 1, cell.z());
                while (logs.contains(up)) {
                    upper.add(up);
                    trunkCells.add(up);
                    up = new Pos(up.x(), up.y() + 1, up.z());
                }
            }
            upper.sort(Comparator.comparingInt(Pos::y));
            trees.add(new Tree(List.copyOf(base), upper, new ArrayList<>()));
        }
        // Everything else is a branch of the nearest trunk (by horizontal distance to its base).
        for (Pos log : logs) {
            if (trunkCells.contains(log)) {
                continue;
            }
            Tree owner = null;
            long best = Long.MAX_VALUE;
            for (Tree tree : trees) {
                long dist = horizontalDistSq(log, centroid(tree.base()));
                if (dist < best) {
                    best = dist;
                    owner = tree;
                }
            }
            if (owner != null) {
                owner.branches().add(log);
            }
        }
        for (Tree tree : trees) {
            tree.branches().sort(Comparator.comparingInt(Pos::y));
        }
        return trees;
    }

    /** The tree whose base is nearest the anchor — "our" tree, since the anchor is a trunk base. */
    public static Optional<Tree> nearest(List<Tree> trees, Pos anchor) {
        Tree best = null;
        long bestDist = Long.MAX_VALUE;
        for (Tree tree : trees) {
            long dist = horizontalDistSq(anchor, centroid(tree.base()));
            if (dist < bestDist) {
                bestDist = dist;
                best = tree;
            }
        }
        return Optional.ofNullable(best);
    }

    private static List<List<Pos>> cluster(List<Pos> cells) {
        List<List<Pos>> clusters = new ArrayList<>();
        Set<Pos> unvisited = new HashSet<>(cells);
        for (Pos seed : cells) {
            if (!unvisited.remove(seed)) {
                continue;
            }
            List<Pos> cluster = new ArrayList<>();
            Deque<Pos> frontier = new ArrayDeque<>();
            frontier.add(seed);
            cluster.add(seed);
            while (!frontier.isEmpty()) {
                Pos p = frontier.poll();
                for (Pos other : new ArrayList<>(unvisited)) {
                    if (Math.abs(other.x() - p.x()) <= 1 && Math.abs(other.y() - p.y()) <= 1
                            && Math.abs(other.z() - p.z()) <= 1) {
                        unvisited.remove(other);
                        cluster.add(other);
                        frontier.add(other);
                    }
                }
            }
            clusters.add(cluster);
        }
        return clusters;
    }

    private static Pos centroid(List<Pos> cells) {
        if (cells.isEmpty()) {
            return new Pos(0, 0, 0);
        }
        int x = 0;
        int y = 0;
        int z = 0;
        for (Pos p : cells) {
            x += p.x();
            y += p.y();
            z += p.z();
        }
        return new Pos(Math.round((float) x / cells.size()), Math.round((float) y / cells.size()),
                Math.round((float) z / cells.size()));
    }

    private static long horizontalDistSq(Pos a, Pos b) {
        long dx = a.x() - b.x();
        long dz = a.z() - b.z();
        return dx * dx + dz * dz;
    }
}
