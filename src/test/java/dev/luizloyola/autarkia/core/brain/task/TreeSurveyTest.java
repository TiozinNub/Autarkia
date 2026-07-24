package dev.luizloyola.autarkia.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.autarkia.core.brain.knowledge.BlockKind;
import dev.luizloyola.autarkia.core.brain.knowledge.FakeProbe;
import dev.luizloyola.autarkia.core.brain.sense.Pos;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Individuation: grounded trunks, 2×2 clustering, the middle split, floating remnants. */
class TreeSurveyTest {

    private final FakeProbe probe = new FakeProbe();
    private final Map<Pos, BlockKind> blocks = new HashMap<>();

    private void trunk(int x, int z, int baseY, int logs) {
        for (int y = baseY; y < baseY + logs; y++) {
            blocks.put(new Pos(x, y, z), BlockKind.LOG);
            probe.set(x, y, z, BlockKind.LOG);
        }
    }

    private void log(int x, int y, int z) {
        blocks.put(new Pos(x, y, z), BlockKind.LOG);
        probe.set(x, y, z, BlockKind.LOG);
    }

    @Test
    void aSingleOakIsOneTreeWithItsStumpAndUpperLogs() {
        trunk(10, 10, 64, 4);
        List<TreeSurvey.Tree> trees = TreeSurvey.survey(blocks, probe);

        assertEquals(1, trees.size());
        TreeSurvey.Tree tree = trees.get(0);
        assertEquals(List.of(new Pos(10, 64, 10)), tree.base(), "stump = the grounded log");
        assertEquals(3, tree.upper().size());
        assertEquals(new Pos(10, 65, 10), tree.upper().get(0), "upper sorted bottom-up");
        assertTrue(tree.branches().isEmpty());
    }

    @Test
    void aFusedPairSplitsBranchesDownTheMiddle() {
        trunk(10, 10, 64, 4);
        trunk(14, 10, 64, 4);
        log(11, 66, 10); // branch nearer trunk A
        log(13, 66, 10); // branch nearer trunk B

        List<TreeSurvey.Tree> trees = TreeSurvey.survey(blocks, probe);
        assertEquals(2, trees.size());
        TreeSurvey.Tree a = TreeSurvey.nearest(trees, new Pos(10, 64, 10)).orElseThrow();
        TreeSurvey.Tree b = TreeSurvey.nearest(trees, new Pos(14, 64, 10)).orElseThrow();
        assertEquals(List.of(new Pos(11, 66, 10)), a.branches());
        assertEquals(List.of(new Pos(13, 66, 10)), b.branches());
    }

    @Test
    void aGiantTwoByTwoIsOneTrunkOfFourColumns() {
        trunk(10, 10, 64, 6);
        trunk(11, 10, 64, 6);
        trunk(10, 11, 64, 6);
        trunk(11, 11, 64, 6);

        List<TreeSurvey.Tree> trees = TreeSurvey.survey(blocks, probe);
        assertEquals(1, trees.size(), "adjacent bases cluster into ONE tree");
        assertEquals(4, trees.get(0).base().size(), "four stump logs -> four replant sites");
        assertEquals(20, trees.get(0).upper().size());
    }

    @Test
    void aFloatingRemnantHasNoTrunks() {
        log(10, 66, 10); // hovering logs, nothing grounded
        log(10, 67, 10);

        assertTrue(TreeSurvey.survey(blocks, probe).isEmpty(),
                "no grounded base -> zero trees; the chopper falls back to reach-what-you-can");
    }
}
