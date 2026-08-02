package dev.luizloyola.autarkia.core.tree;

import dev.luizloyola.anima.core.brain.knowledge.BlockKind;
import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Splits a scanned box of wood-and-leaf cells into connected masses — the survey-side stand-in
 * for what perception hands {@link TreeShape}: a direct scan must reconnect its cells first, or
 * two groves a field apart would be one "mass". Connectivity is {@link TreeShape#attached} —
 * wood to wood 26-way, a leaf through its six faces only — so a mass never spans a boundary
 * the split could not cross anyway.
 *
 * <p>Deterministic: seeds in {@link TreeShape}'s low/west/north order, cells in discovery
 * order, so one world scan always yields the same masses in the same order.
 */
public final class TreeMasses {
    /** The same total order {@code TreeShape} sorts by — one ordering vocabulary per package. */
    private static final Comparator<Pos> ORDER = Comparator.comparingInt(Pos::y)
            .thenComparingInt(Pos::x).thenComparingInt(Pos::z);

    private TreeMasses() {
    }

    /** The attachment-connected masses of {@code cells}, each a sub-map of the input. */
    public static List<Map<Pos, BlockKind>> connect(Map<Pos, BlockKind> cells) {
        List<Pos> seeds = new ArrayList<>(cells.keySet());
        seeds.sort(ORDER);
        Set<Pos> claimed = new HashSet<>();
        List<Map<Pos, BlockKind>> masses = new ArrayList<>();
        for (Pos seed : seeds) {
            if (!claimed.add(seed)) {
                continue;
            }
            Map<Pos, BlockKind> mass = new LinkedHashMap<>();
            mass.put(seed, cells.get(seed));
            Deque<Pos> frontier = new ArrayDeque<>();
            frontier.add(seed);
            while (!frontier.isEmpty()) {
                Pos cell = frontier.poll();
                BlockKind kind = cells.get(cell);
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            Pos next = new Pos(cell.x() + dx, cell.y() + dy, cell.z() + dz);
                            BlockKind nextKind = cells.get(next);
                            if (nextKind != null && TreeShape.attached(kind, nextKind, dx, dy, dz)
                                    && claimed.add(next)) {
                                mass.put(next, nextKind);
                                frontier.add(next);
                            }
                        }
                    }
                }
            }
            masses.add(mass);
        }
        return masses;
    }
}
