package dev.luizloyola.autarkia.core.tree;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.knowledge.BlockKind;
import dev.luizloyola.anima.core.brain.knowledge.FakeProbe;
import dev.luizloyola.anima.core.brain.knowledge.Region;
import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Wood a posted box must take that no rule will call a tree.
 *
 * <p>{@link TreeRule} refuses a crownless trunk on purpose, so a settler never reads a log cabin as
 * forest. Felling makes that shape: a fused canopy leaves with the tree that owned it and the
 * neighbour it sheltered stops being a tree before anyone reaches it (live, 2026-08-23).
 */
class TreeResidueTest {

    private static final int GROUND = FakeProbe.GROUND_Y;

    private static Region box() {
        return new Region(new Pos(-8, GROUND - 4, -8), new Pos(24, GROUND + 20, 24));
    }

    @Test
    void aCrownlessTrunkIsResidueAndALivingTreeIsNot() {
        FakeProbe probe = new FakeProbe();
        probe.placeOak(0, 0);
        for (int y = GROUND + 1; y <= GROUND + 3; y++) {
            probe.set(16, y, 16, BlockKind.LOG);
        }

        List<Pos> residue = TreeClearing.INSTANCE.residue(box(), probe);

        assertEquals(List.of(new Pos(16, GROUND + 1, 16)), residue,
                "the stub is named at its lowest log, and the oak is left to the ordinary path "
                        + "— its crown blocks motion, so its surface is a leaf, not a log");
    }

    @Test
    void theAnchorIsTheLowestLogOfTheColumn() {
        FakeProbe probe = new FakeProbe();
        for (int y = GROUND + 1; y <= GROUND + 6; y++) {
            probe.set(4, y, 4, BlockKind.LOG);
        }

        assertEquals(List.of(new Pos(4, GROUND + 1, 4)), TreeClearing.INSTANCE.residue(box(), probe),
                "matching TreeRule's own lowest-base-cell anchor, so a stub and a tree name the "
                        + "same kind of place");
    }

    @Test
    void barePlainsYieldNothing() {
        assertTrue(TreeClearing.INSTANCE.residue(box(), new FakeProbe()).isEmpty(),
                "the scan must cost an empty box nothing but its reads");
    }

    @Test
    void woodOutsideTheBoxIsNotReported() {
        FakeProbe probe = new FakeProbe();
        for (int y = GROUND + 1; y <= GROUND + 2; y++) {
            probe.set(100, y, 100, BlockKind.LOG);
        }

        assertTrue(TreeClearing.INSTANCE.residue(box(), probe).isEmpty(),
                "the bounds are the safeguard — what an operator drew is the whole licence");
    }
}
