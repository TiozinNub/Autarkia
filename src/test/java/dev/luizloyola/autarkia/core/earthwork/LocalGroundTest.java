package dev.luizloyola.autarkia.core.earthwork;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.knowledge.BlockKind;
import dev.luizloyola.anima.core.brain.knowledge.FakeProbe;
import org.junit.jupiter.api.Test;

/** The dig rule: above the ground round a column, so a lone bump goes and a terrace's edge stays. */
class LocalGroundTest {

    private static final int G = FakeProbe.GROUND_Y;

    private final FakeProbe probe = new FakeProbe();

    private void raise(int x, int z, int height) {
        for (int y = G + 1; y <= height; y++) {
            probe.set(x, y, z, BlockKind.OTHER);
        }
    }

    @Test
    void flatGroundIsNeverCut() {
        assertFalse(new LocalGround(probe).standsAbove(0, 0, G));
    }

    @Test
    void aLoneBumpOneHighIsCutToTheFlatAndNoFurther() {
        raise(0, 0, G + 1);
        LocalGround local = new LocalGround(probe);
        assertTrue(local.standsAbove(0, 0, G + 1));
        assertFalse(local.standsAbove(0, 0, G));
    }

    @Test
    void twoBumpsSideBySideAreBothCut() {
        raise(0, 0, G + 1);
        raise(1, 0, G + 1);
        LocalGround local = new LocalGround(probe);
        assertTrue(local.standsAbove(0, 0, G + 1));
        assertTrue(local.standsAbove(1, 0, G + 1));
    }

    @Test
    void aPillarIsCutToTheFlatHoweverTall() {
        raise(0, 0, G + 13);
        LocalGround local = new LocalGround(probe);
        assertTrue(local.standsAbove(0, 0, G + 1), "its own height is no part of the ground round it");
        assertFalse(local.standsAbove(0, 0, G));
    }

    @Test
    void aTerracesStraightEdgeOneHighStays() {
        for (int x = 0; x <= 4; x++) {
            for (int z = -4; z <= 4; z++) {
                raise(x, z, G + 1);
            }
        }
        assertFalse(new LocalGround(probe).standsAbove(0, 0, G + 1));
    }

    @Test
    void aHolesFloorIsNeverCut() {
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                if (x != 0 || z != 0) {
                    raise(x, z, G + 1);
                }
            }
        }
        assertFalse(new LocalGround(probe).standsAbove(0, 0, G));
    }

    @Test
    void groundOutOfReachRoundItIsNeverCut() {
        raise(0, 0, G + 1);
        probe.markUnloaded(2, 2);
        assertFalse(new LocalGround(probe).standsAbove(0, 0, G + 1));
    }
}
