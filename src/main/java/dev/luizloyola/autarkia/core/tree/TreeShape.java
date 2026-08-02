package dev.luizloyola.autarkia.core.tree;

import dev.luizloyola.anima.core.brain.knowledge.BlockKind;
import dev.luizloyola.anima.core.brain.knowledge.BlockProbe;
import dev.luizloyola.anima.core.brain.knowledge.RegionGrowth;
import dev.luizloyola.anima.core.log.Entry;
import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Splits a scanned mass of logs and leaves into individual trees. {@link TreeRule} individuates
 * through this and whatever fells them must too, so perception and the axe never disagree about
 * where one tree ends. Worldgen and 26-way growth ({@link RegionGrowth}) fuse canopies; this is the
 * seam that puts them back.
 *
 * <p>A <b>trunk</b> is the vertical log run above a <em>grounded base</em>: a log whose support is
 * real ground (the probe says {@link BlockKind#OTHER}) and whose overhead cell is the next log of
 * its column. Grounded alone is not base, or a fallen log would read as an N-wide stump; overhead
 * must be WOOD, since a leaf would let a fallen log under a low canopy back in. Adjacent base cells
 * cluster, so a 2×2 giant is one trunk of four columns.
 *
 * <p>Every other cell is assigned by GROWTH: ownership spreads from each trunk wave by wave along
 * {@link #attached} steps (wood to wood 26-way, leaves through their six faces only), so a tree is
 * connected by construction — pure nearest-centroid handed a tall spruce's top canopy to a short
 * bushy neighbour thirty cells of air away. Two waves reaching a cell in the same round is the true
 * contested boundary: nearest base centroid decides, ties to the earlier trunk.
 *
 * <p>A mass with no grounded base splits into nothing, and so does a grounded trunk that ends the
 * wave with no leaves — a fallen log lies flat with every log "grounded", and leaves are what prove
 * a tree rather than a woodpile. A cell whose only paths run through a foreign trunk's wood stays
 * unassigned; {@link SplitReport} carries it so nothing goes silent.
 *
 * <p>Deterministic throughout, because the anchor this produces is a memory's identity and must not
 * depend on hash order.
 */
public final class TreeShape {
    /** Low-to-high, then west-to-east, then north-to-south: a total order over cells. */
    private static final Comparator<Pos> ORDER = Comparator.comparingInt(Pos::y)
            .thenComparingInt(Pos::x).thenComparingInt(Pos::z);

    private TreeShape() {
    }

    /**
     * One individuated tree inside a scanned mass: {@code base} is its stump layer (one cell per
     * 1×1 tree, four for a 2×2 giant), {@code column} the logs standing directly above them,
     * {@code branches} the logs assigned to it anywhere else, {@code leaves} its crown —
     * {@link #split} never returns a trunk with an empty crown.
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
            if (logs.contains(below)
                    || probe.at(below.x(), below.y(), below.z()) != BlockKind.OTHER) {
                continue; // not standing on real ground: no stump candidate
            }
            // Grounded is not enough — a base cell is the foot of a COLUMN, so the cell overhead
            // must be the next log, wood and nothing else (decision: Luiz). A fallen log is
            // grounded along its length and used to cluster into a neighbour's base as extra stump
            // cells, one sapling replanted each. An overhead LEAF would let it back in under a low
            // canopy, so a directly crowned one-log trunk (an azalea) is KNOWINGLY sacrificed;
            // fallen wood still joins the tree it touches, as branches.
            if (blocks.get(new Pos(log.x(), log.y() + 1, log.z())) == BlockKind.LOG) {
                baseCells.add(log);
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
        // What the waves may claim: everything except trunks (pre-owned) and foreign wood, which
        // is excluded outright and OPAQUE to growth — whatever stands past it is the foreign
        // tree's to explain.
        Set<Pos> assignable = new LinkedHashSet<>();
        for (Pos log : logs) {
            if (!trunkCells.contains(log) && !restsOnForeignTrunk(log, logs, probe)) {
                assignable.add(log);
            }
        }
        assignable.addAll(leaves);

        Map<Pos, Integer> owner = new LinkedHashMap<>();
        List<Pos> frontier = new ArrayList<>();
        for (int i = 0; i < clusters.size(); i++) {
            for (Pos cell : clusters.get(i)) {
                owner.put(cell, i);
                frontier.add(cell);
            }
            for (Pos cell : columns.get(i)) {
                owner.put(cell, i);
                frontier.add(cell);
            }
        }
        frontier.sort(ORDER);
        while (!frontier.isEmpty()) {
            // One wave: every tree grows one attachment step. A cell two waves reach in the
            // same round is the genuinely contested boundary, and only there does the old
            // nearest-centroid rule speak (ties to the earlier trunk). The claim map makes the
            // outcome independent of iteration order.
            Map<Pos, Integer> claims = new LinkedHashMap<>();
            for (Pos cell : frontier) {
                int tree = owner.get(cell);
                BlockKind kind = blocks.get(cell);
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            Pos next = new Pos(cell.x() + dx, cell.y() + dy, cell.z() + dz);
                            BlockKind nextKind = blocks.get(next);
                            if (!attached(kind, nextKind, dx, dy, dz)
                                    || !assignable.contains(next) || owner.containsKey(next)) {
                                continue;
                            }
                            // Ownership enters a LOG only from another LOG: branches hang
                            // from wood, never from leaves. A crownless stump under a grove's
                            // canopy is in the mass (the leaves touch it), but it is
                            // nobody's branch (the lone-stump lesson, again).
                            if (nextKind == BlockKind.LOG && kind != BlockKind.LOG) {
                                continue;
                            }
                            Integer rival = claims.get(next);
                            claims.put(next, rival == null ? tree
                                    : closer(centers, next, rival, tree));
                        }
                    }
                }
            }
            owner.putAll(claims);
            frontier = new ArrayList<>(claims.keySet());
            frontier.sort(ORDER);
        }
        for (Map.Entry<Pos, Integer> claimed : owner.entrySet()) {
            Pos cell = claimed.getKey();
            if (trunkCells.contains(cell)) {
                continue;
            }
            (logs.contains(cell) ? branches : crowns).get(claimed.getValue()).add(cell);
        }
        List<Trunk> trunks = new ArrayList<>(clusters.size());
        for (int i = 0; i < clusters.size(); i++) {
            if (crowns.get(i).isEmpty()) {
                // A crownless trunk is a woodpile, not a tree: a fallen log lies flat, every
                // log "grounded", and read as an N-wide tree (decision: Luiz, 2026-08-02). Its
                // wood goes unclaimed and the report carries it as stray.
                continue;
            }
            branches.get(i).sort(ORDER);
            crowns.get(i).sort(ORDER);
            trunks.add(new Trunk(clusters.get(i), columns.get(i), branches.get(i), crowns.get(i)));
        }
        return trunks;
    }

    /**
     * Whether ownership may pass between two touching cells (decision: Luiz, 2026-08-02). Wood
     * attaches to wood across all 26 neighbours, because real branches step diagonally; a LEAF
     * attaches through its six faces only (vanilla's own leaf-distance rule), so interleaving
     * canopies never bleed into each other.
     */
    static boolean attached(BlockKind from, BlockKind to, int dx, int dy, int dz) {
        boolean face = Math.abs(dx) + Math.abs(dy) + Math.abs(dz) == 1;
        return face || (from == BlockKind.LOG && to == BlockKind.LOG
                && (dx != 0 || dy != 0 || dz != 0));
    }

    /** Which of two same-round claimants is nearer the cell — the seam's tie-break. */
    private static int closer(List<Pos> centers, Pos cell, int a, int b) {
        long distA = horizontalDistSq(cell, centers.get(a));
        long distB = horizontalDistSq(cell, centers.get(b));
        if (distA != distB) {
            return distA < distB ? a : b;
        }
        return Math.min(a, b);
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
