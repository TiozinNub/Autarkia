package dev.luizloyola.autarkia.core.brain.knowledge;

import dev.luizloyola.autarkia.core.brain.sense.Pos;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Splits a scanned mass of logs and leaves into individual trees — the one answer shared by
 * {@link TreeRule}, which remembers them, and {@code TreeSurvey}, which fells them, so perception
 * and the axe cannot disagree. Worldgen and 26-way growth ({@link RegionGrowth}) fuse canopies;
 * this is the seam that puts them back.
 *
 * <p>A <b>trunk</b> is the vertical log run above a <em>grounded base</em> — a log cell supported
 * by real ground ({@link BlockKind#OTHER}) — with adjacent base cells clustered, so a 2×2 giant
 * is one trunk of four columns. Every other cell, log or leaf, goes to the horizontally nearest
 * base centroid: the perpendicular-bisector split, generalized. A mass with no grounded base (a
 * floating remnant of an earlier partial chop) splits into nothing.
 *
 * <p>Deterministic: base cells and clusters are sorted before assignment, because the anchor is a
 * memory's identity and must not depend on hash order.
 */
public final class TreeShape {
    /** Low-to-high, then west-to-east, then north-to-south: a total order over cells. */
    private static final Comparator<Pos> ORDER = Comparator.comparingInt(Pos::y)
            .thenComparingInt(Pos::x).thenComparingInt(Pos::z);

    private TreeShape() {
    }

    /**
     * One individuated tree inside a scanned mass: its stump layer ({@code base} — one cell per
     * 1×1 tree, four for a 2×2 giant), the logs standing directly above those cells
     * ({@code column}), the logs assigned to it that stand anywhere else ({@code branches}), and
     * the leaves assigned to it ({@code leaves} — its crown, the thing that proves it a tree
     * rather than a woodpile).
     */
    public record Trunk(List<Pos> base, List<Pos> column, List<Pos> branches, List<Pos> leaves) {
        public int logCount() {
            return base.size() + column.size() + branches.size();
        }
    }

    /** Splits a scanned collection into its trees. Empty when nothing in it is grounded. */
    public static List<Trunk> split(Map<Pos, BlockKind> blocks, BlockProbe probe) {
        Set<Pos> logs = new LinkedHashSet<>();
        List<Pos> leaves = new ArrayList<>();
        for (Map.Entry<Pos, BlockKind> entry : blocks.entrySet()) {
            if (entry.getValue() == BlockKind.LOG) {
                logs.add(entry.getKey());
            } else if (entry.getValue() == BlockKind.LEAVES) {
                leaves.add(entry.getKey());
            }
        }
        List<Pos> baseCells = new ArrayList<>();
        for (Pos log : logs) {
            Pos below = new Pos(log.x(), log.y() - 1, log.z());
            if (!logs.contains(below)
                    && probe.at(below.x(), below.y(), below.z()) == BlockKind.OTHER) {
                baseCells.add(log); // stands on a non-tree block: a stump candidate
            }
        }
        baseCells.sort(ORDER);
        List<List<Pos>> clusters = cluster(baseCells);
        clusters.sort(Comparator.comparing(c -> c.get(0), ORDER));

        List<List<Pos>> columns = new ArrayList<>();
        List<Pos> centers = new ArrayList<>();
        Set<Pos> trunkCells = new HashSet<>();
        for (List<Pos> base : clusters) {
            List<Pos> column = new ArrayList<>();
            for (Pos cell : base) {
                trunkCells.add(cell);
                Pos up = new Pos(cell.x(), cell.y() + 1, cell.z());
                while (logs.contains(up)) {
                    column.add(up);
                    trunkCells.add(up);
                    up = new Pos(up.x(), up.y() + 1, up.z());
                }
            }
            column.sort(ORDER);
            columns.add(column);
            centers.add(centroid(base));
        }
        List<List<Pos>> branches = new ArrayList<>();
        List<List<Pos>> crowns = new ArrayList<>();
        for (int i = 0; i < clusters.size(); i++) {
            branches.add(new ArrayList<>());
            crowns.add(new ArrayList<>());
        }
        for (Pos log : logs) {
            if (!trunkCells.contains(log)) {
                if (restsOnForeignTrunk(log, logs, probe)) {
                    continue; // a trunk the scan cut in half — its own tree, never a branch
                }
                int owner = nearest(centers, log);
                if (owner >= 0) {
                    branches.get(owner).add(log);
                }
            }
        }
        for (Pos leaf : leaves) {
            int owner = nearest(centers, leaf);
            if (owner >= 0) {
                crowns.get(owner).add(leaf);
            }
        }
        List<Trunk> trunks = new ArrayList<>(clusters.size());
        for (int i = 0; i < clusters.size(); i++) {
            trunks.add(new Trunk(clusters.get(i), columns.get(i), branches.get(i), crowns.get(i)));
        }
        return trunks;
    }

    /**
     * Whether this log's supporting run bottoms out on a LOG the scan never collected — the
     * signature of a neighbouring trunk a partial growth cut in half, which nearest-centroid
     * otherwise adopts as "branches", manufacturing a lone stump when they are felled (caught
     * live 2026-07-27). Such wood belongs to whatever owns those logs.
     */
    private static boolean restsOnForeignTrunk(Pos log, Set<Pos> logs, BlockProbe probe) {
        Pos below = new Pos(log.x(), log.y() - 1, log.z());
        while (logs.contains(below)) {
            below = new Pos(below.x(), below.y() - 1, below.z());
        }
        return probe.at(below.x(), below.y(), below.z()) == BlockKind.LOG;
    }

    /** The horizontal centre of a base cluster — what "nearest trunk" measures against. */
    public static Pos centroid(List<Pos> cells) {
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

    /** Squared horizontal distance — height never decides which trunk a cell belongs to. */
    public static long horizontalDistSq(Pos a, Pos b) {
        long dx = a.x() - b.x();
        long dz = a.z() - b.z();
        return dx * dx + dz * dz;
    }

    /** Index of the nearest centre, or -1 when there are none; ties go to the earlier trunk. */
    private static int nearest(List<Pos> centers, Pos cell) {
        int owner = -1;
        long best = Long.MAX_VALUE;
        for (int i = 0; i < centers.size(); i++) {
            long dist = horizontalDistSq(cell, centers.get(i));
            if (dist < best) {
                best = dist;
                owner = i;
            }
        }
        return owner;
    }

    /** Groups base cells that touch (Chebyshev ≤ 1) — one group per trunk, 2×2 giants included. */
    private static List<List<Pos>> cluster(List<Pos> cells) {
        List<List<Pos>> clusters = new ArrayList<>();
        Set<Pos> unvisited = new LinkedHashSet<>(cells);
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
            cluster.sort(ORDER);
            clusters.add(cluster);
        }
        return clusters;
    }
}
