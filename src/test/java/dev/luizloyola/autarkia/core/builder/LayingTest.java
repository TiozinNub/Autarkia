package dev.luizloyola.autarkia.core.builder;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.act.Placing;
import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Whether what a cell holds is a step done. */
class LayingTest {

    private static Laying step(String item) {
        return new Laying(Section.WALLS, Placing.of(item, new Pos(6, -60, 5)), List.of(), new Pos(6, -60, 6), 1);
    }

    @Test
    void aDirtWallGrownOverWithGrassIsStillTheWall() {
        assertTrue(step("minecraft:dirt").standsAs("minecraft:dirt", Map.of()));
        assertTrue(step("minecraft:dirt").standsAs("minecraft:grass_block", Map.of()),
                "the hut's dirt read as somebody's grass, and the step placed into its own block");
        assertFalse(step("minecraft:dirt").standsAs("minecraft:stone", Map.of()));
        assertFalse(step("minecraft:oak_planks").standsAs("minecraft:grass_block", Map.of()));
    }
}
