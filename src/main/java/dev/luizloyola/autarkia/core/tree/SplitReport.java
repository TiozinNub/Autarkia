package dev.luizloyola.autarkia.core.tree;

import dev.luizloyola.anima.core.brain.knowledge.BlockKind;
import dev.luizloyola.anima.core.brain.knowledge.BlockProbe;
import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One mass's {@link TreeShape#split} outcome with its silences made explicit. The split returns
 * only what it assigned; the declined cells are where every historical individuation bug hid,
 * so anything that shows a split has to account for them:
 *
 * <ul>
 *   <li>{@link #strayLogs} — wood no tree was allowed to claim: standing on a log the scan
 *       never collected (the foreign-trunk rule), or unreachable from every trunk by
 *       attachment.
 *   <li>{@link #strayLeaves} — leaves no wave could reach, usually that foreign trunk's
 *       canopy: they belong to whatever owns it, not to whoever is closest.
 *   <li>{@link #treeless()} — no tree at all: no grounded base (a floating remnant, a pure
 *       leaf blob), or grounded wood with no crown (a fallen log). It splits into nothing, on
 *       purpose.
 * </ul>
 */
public record SplitReport(List<TreeShape.Trunk> trees, List<Pos> strayLogs,
                          List<Pos> strayLeaves) {

    /** Splits {@code mass} and reconciles every input cell against what came back assigned. */
    public static SplitReport of(Map<Pos, BlockKind> mass, BlockProbe probe) {
        List<TreeShape.Trunk> trees = TreeShape.split(mass, probe);
        if (trees.isEmpty()) {
            // Nothing was assigned, so nothing is "stray" — the mass as a whole is the finding.
            return new SplitReport(trees, List.of(), List.of());
        }
        Set<Pos> assigned = new HashSet<>();
        for (TreeShape.Trunk tree : trees) {
            assigned.addAll(tree.base());
            assigned.addAll(tree.column());
            assigned.addAll(tree.branches());
            assigned.addAll(tree.leaves());
        }
        List<Pos> strayLogs = new ArrayList<>();
        List<Pos> strayLeaves = new ArrayList<>();
        for (Map.Entry<Pos, BlockKind> cell : mass.entrySet()) {
            if (assigned.contains(cell.getKey())) {
                continue;
            }
            if (cell.getValue() == BlockKind.LOG) {
                strayLogs.add(cell.getKey());
            } else if (cell.getValue() == BlockKind.LEAVES) {
                strayLeaves.add(cell.getKey());
            }
        }
        return new SplitReport(trees, List.copyOf(strayLogs), List.copyOf(strayLeaves));
    }

    /** Whether the mass produced no trees at all — floating wood, a fallen log, loose leaves. */
    public boolean treeless() {
        return trees.isEmpty();
    }
}
