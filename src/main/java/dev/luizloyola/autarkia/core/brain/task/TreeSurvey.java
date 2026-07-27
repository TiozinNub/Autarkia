package dev.luizloyola.autarkia.core.brain.task;

import dev.luizloyola.autarkia.core.brain.knowledge.BlockKind;
import dev.luizloyola.autarkia.core.brain.knowledge.BlockProbe;
import dev.luizloyola.autarkia.core.brain.knowledge.TreeShape;
import dev.luizloyola.autarkia.core.brain.sense.Pos;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The chop's view of a scanned grove — "whose tree is this?", answered by {@link TreeShape}, the
 * same split perception uses to REMEMBER trees, so the axe and the memory can never disagree. One
 * tree per errand (decision: Luiz); neighbors stay standing, remembered as trees of their own.
 *
 * <p>The order this adds: trunk logs bottom-up, branches outermost-first then higher-first, the
 * never-orphan sequence. A structure with no grounded base surveys as zero trees.
 */
public final class TreeSurvey {
    private TreeSurvey() {
    }

    /**
     * One individuated tree: the stump layer ({@code base} — one cell per 1×1 tree, four for a
     * 2×2 giant), the trunk logs above it bottom-up ({@code upper}), and its assigned
     * {@code branches} in felling order. Base cells double as the replant sites: one sapling per
     * stump log (decision: Luiz — 1×1 vs 2×2 replanting falls out automatically).
     */
    public record Tree(List<Pos> base, List<Pos> upper, List<Pos> branches) {
        public int logCount() {
            return base.size() + upper.size() + branches.size();
        }
    }

    public static List<Tree> survey(Map<Pos, BlockKind> blocks, BlockProbe probe) {
        List<Tree> trees = new ArrayList<>();
        for (TreeShape.Trunk trunk : TreeShape.split(blocks, probe)) {
            List<Pos> upper = new ArrayList<>(trunk.column());
            upper.sort(Comparator.comparingInt(Pos::y));
            // Outermost-first (then higher-first): felling a chain from its tip inward never
            // orphans wood — the never-orphan rule for branches.
            Pos center = TreeShape.centroid(trunk.base());
            List<Pos> branches = new ArrayList<>(trunk.branches());
            branches.sort(Comparator
                    .comparingLong((Pos p) -> TreeShape.horizontalDistSq(p, center)).reversed()
                    .thenComparing(Comparator.comparingInt(Pos::y).reversed()));
            trees.add(new Tree(List.copyOf(trunk.base()), upper, branches));
        }
        return trees;
    }

    /** The tree whose base is nearest the anchor — "our" tree, since the anchor is a trunk base. */
    public static Optional<Tree> nearest(List<Tree> trees, Pos anchor) {
        Tree best = null;
        long bestDist = Long.MAX_VALUE;
        for (Tree tree : trees) {
            long dist = TreeShape.horizontalDistSq(anchor, TreeShape.centroid(tree.base()));
            if (dist < bestDist) {
                bestDist = dist;
                best = tree;
            }
        }
        return Optional.ofNullable(best);
    }
}
