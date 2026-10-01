package dev.luizloyola.autarkia.core.earthwork;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.knowledge.BlockKind;
import dev.luizloyola.anima.core.brain.knowledge.FakeProbe;
import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.List;
import org.junit.jupiter.api.Test;

class CutCellsTest {

    private static final int G = FakeProbe.GROUND_Y;

    @Test
    void aCellBesideWaterIsLeftStanding() {
        FakeProbe probe = new FakeProbe();
        probe.set(1, G + 1, 1, BlockKind.OTHER);
        probe.set(2, G + 1, 1, BlockKind.WATER);
        assertFalse(CutCells.cuttable(probe, new Pos(1, G + 1, 1)));
    }

    @Test
    void aCellUnderLavaIsLeftStanding() {
        FakeProbe probe = new FakeProbe();
        probe.setId(4, G + 1, 4, "minecraft:lava");
        assertFalse(CutCells.cuttable(probe, new Pos(4, G, 4)));
    }

    @Test
    void groundIsCutAndAirIsNot() {
        FakeProbe probe = new FakeProbe();
        assertTrue(CutCells.cuttable(probe, new Pos(1, G, 5)));
        assertFalse(CutCells.cuttable(probe, new Pos(1, G + 1, 5)));
    }

    @Test
    void theWalkGoesToTheNearestNext() {
        List<Pos> walk = CutCells.walk(List.of(new Pos(9, 0, 0), new Pos(1, 0, 0), new Pos(5, 0, 0)),
                new Pos(0, 0, 0));
        assertEquals(List.of(new Pos(1, 0, 0), new Pos(5, 0, 0), new Pos(9, 0, 0)), walk);
    }
}
