package dev.luizloyola.autarkia.core.nav;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class NavGridsTest {

    private static boolean nearDrop(AsciiWorld world, int x, int y, int z) {
        return NavGrids.isNearDeepDrop(world, AgentProfile.PERSON.maxDrop(), x, y, z);
    }

    @Test
    void openGroundIsNotAnEdge() {
        AsciiWorld world = AsciiWorld.of(
                "111",
                "111",
                "111");
        assertFalse(nearDrop(world, 1, 1, 1));
    }

    @Test
    void besideABottomlessHoleIsAnEdge() {
        assertTrue(nearDrop(AsciiWorld.of("11 "), 1, 1, 0));
    }

    @Test
    void aChasmDeeperThanMaxDropIsAnEdge() {
        // 5-high plateau beside height-1 ground: a 4-block fall, one more than maxDrop.
        assertTrue(nearDrop(AsciiWorld.of("551"), 1, 5, 0));
    }

    @Test
    void aSurvivableDropIsNotAnEdge() {
        // 4-high plateau beside height-1 ground: a 3-block drop is an everyday move.
        assertFalse(nearDrop(AsciiWorld.of("441"), 1, 4, 0));
    }

    @Test
    void besideLavaIsAnEdge() {
        assertTrue(nearDrop(AsciiWorld.of("11L"), 1, 1, 0));
    }

    @Test
    void besideWaterIsAnEdgeWhileSwimmingIsUnimplemented() {
        assertTrue(nearDrop(AsciiWorld.of("11W"), 1, 1, 0));
    }

    @Test
    void besideAWallIsNotAnEdge() {
        assertFalse(nearDrop(AsciiWorld.of("11#"), 1, 1, 0));
    }
}
