package dev.luizloyola.autarkia.core.builder;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.luizloyola.autarkia.core.bp.BuildPlan;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SectionsTest {

    /** Cells of the shipped basic house, two beds, a crafting corner and the attic, read by hand. */
    @Test
    void theBasicHouseSplitsAsABuilderWouldSayIt() throws IOException {
        BuildPlan plan = BuildOrderTest.plan(BuildOrderTest.bind(BuildOrderTest.read(
                "/data/autarkia/autarkia/blueprint/basic_wooden_house.bp")),
                Map.of("beds", "2", "base", "lv1", "attic", "has"));
        Map<Cell, Step> at = new HashMap<>();
        for (Step step : Sections.of(plan, BuildOrderTest.DICT)) {
            step.cells().forEach(cell -> at.put(cell, step));
        }
        assertEquals(Section.FLOOR, at.get(new Cell(1, 3, 3)).section(), "a floor plank");
        assertEquals(Section.FLOOR, at.get(new Cell(1, 0, 6)).section(), "the porch step");
        assertEquals(Section.WALLS, at.get(new Cell(3, 1, 2)).section(), "a wall plank");
        assertEquals(Section.WALLS, at.get(new Cell(7, 3, 0)).section(), "a gable");
        assertEquals(Section.CEILING, at.get(new Cell(9, 4, 5)).section(), "the ridge");
        assertEquals(Section.CEILING, at.get(new Cell(5, 0, 3)).section(), "an eave");
        assertEquals(Section.CEILING, at.get(new Cell(5, 2, 2)).section(), "the attic floor over the room");

        Step torch = at.get(new Cell(4, 4, 2));
        assertEquals(Section.LIGHTS, torch.section());
        assertEquals(new Cell(4, 4, 1), torch.holder(), "the north wall");

        Step door = at.get(new Cell(2, 1, 6));
        assertEquals(Section.DOORS, door.section());
        assertEquals(List.of(new Cell(2, 1, 6), new Cell(3, 1, 6)), door.cells(), "placed at its lower half");
        assertEquals(new Cell(1, 1, 6), door.holder());

        Step bed = at.get(new Cell(2, 2, 2));
        assertEquals(Section.INTERIOR, bed.section());
        assertEquals(List.of(new Cell(2, 2, 3), new Cell(2, 2, 2)), bed.cells(), "placed at its foot");

        Step ladder = at.get(new Cell(3, 3, 7));
        assertEquals(Section.WALLS, ladder.section(), "the way up goes up with its wall");
        assertEquals(new Cell(3, 3, 8), ladder.holder(), "the planks the attic put behind it");

        assertEquals(Section.INTERIOR, at.get(new Cell(2, 4, 7)).section(), "a chest");
    }
}
