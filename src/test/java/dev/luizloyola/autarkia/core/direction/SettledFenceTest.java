package dev.luizloyola.autarkia.core.direction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.nav.AsciiWorld;
import dev.luizloyola.anima.core.nav.HandsOff;
import dev.luizloyola.anima.core.nav.MoveCapabilities;
import dev.luizloyola.anima.core.nav.NavGrid;
import dev.luizloyola.anima.core.nav.Path;
import dev.luizloyola.anima.core.nav.PathRequest;
import dev.luizloyola.anima.core.nav.Pathfinder;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A ravine inside HOME takes a deck; a settled spot takes nothing; HOME's ground is never cut. */
class SettledFenceTest {

    private static final MoveCapabilities BODY = new MoveCapabilities(1.8, 1, 3, 3, true, 36, true, true);
    private static final List<int[]> HOME = List.of(new int[] {0, 0, 11, 2});

    /** Six columns of nothing between two banks, x 3..8. */
    private static NavGrid ravine() {
        String row = "111      111";
        return AsciiWorld.of(row, row, row).bounded();
    }

    private static Path cross(HandsOff fence) {
        return Pathfinder.find(ravine(), PathRequest.of(1, 1, 1, 10, 1, 1, BODY.withLaid(6)).keepingOff(fence));
    }

    @Test
    void aNaturalGapInsideHomeIsDecked() {
        Path path = cross(SettledFence.of(HOME, List.of()));
        assertTrue(path.reachedGoal(), "Ruth's ravine: inside her own HOME, and crossed");
        assertEquals(6, path.laid());
    }

    @Test
    void aSettledSpotInsideHomeIsNeverBuiltOn() {
        Path path = cross(SettledFence.of(HOME, List.of(new int[] {5, 0, 5, 2})));
        assertFalse(path.reachedGoal(), "a deck would land on a station's margin");
        assertEquals(0, path.laid());
    }

    @Test
    void nothingIsScaledInsideHome() {
        String row = "111333";
        NavGrid step = AsciiWorld.of(row, row, row).soft(3, 0, 0, 5, 1, 2).regrows(3, 2, 0, 5, 2, 2)
                .bounded();
        PathRequest up = PathRequest.of(1, 1, 1, 5, 3, 1, BODY.withScaling(true));
        assertTrue(Pathfinder.find(step, up).reachedGoal());
        assertFalse(Pathfinder.find(step, up.keepingOff(SettledFence.of(HOME, List.of()))).reachedGoal(),
                "a scale cuts the lip, and nothing is cut inside HOME");
    }
}
