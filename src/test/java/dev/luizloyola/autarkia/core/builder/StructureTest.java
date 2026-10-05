package dev.luizloyola.autarkia.core.builder;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.autarkia.core.bp.Footprint;
import dev.luizloyola.autarkia.core.bp.Placement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** A door is a house's when it is within the walls of a building that stands. */
class StructureTest {

    private static Structure house(Structure.Phase phase) {
        return new Structure(UUID.randomUUID(), "autarkia:basic_wooden_house", 3, Map.of(), Map.of(),
                new Pos(5, 64, 6), Placement.AS_DRAWN, new Footprint(1, 1, 9, 10), new Footprint(0, 0, 11, 12),
                phase, 0L, "");
    }

    @Test
    void aDoorInTheWallsOfAStandingHouseIsAHouses() {
        Pos door = new Pos(5, 65, 1);
        assertTrue(Structure.houseDoor(List.of(house(Structure.Phase.BUILT)), door));
        assertTrue(Structure.houseDoor(List.of(house(Structure.Phase.GROWING)), door), "growing, it still stands");
    }

    @Test
    void aHouseNotYetStandingHasNoDoors() {
        Pos door = new Pos(5, 65, 1);
        for (Structure.Phase phase : List.of(Structure.Phase.SITED, Structure.Phase.LEVELLED,
                Structure.Phase.BUILDING, Structure.Phase.REFUSED)) {
            assertFalse(Structure.houseDoor(List.of(house(phase)), door), phase.name());
        }
    }

    @Test
    void aDoorOutsideTheWallsIsNotTheHouses() {
        List<Structure> built = List.of(house(Structure.Phase.BUILT));
        assertFalse(Structure.houseDoor(built, new Pos(5, 65, 0)), "on the pad, past the walls");
        assertFalse(Structure.houseDoor(built, new Pos(10, 65, 5)), "beside it");
        assertFalse(Structure.houseDoor(built, new Pos(5, 63, 5)), "under its floor");
        assertFalse(Structure.houseDoor(built, new Pos(5, 64 + Structure.WALLS_HIGH + 1, 5)), "above it");
    }
}
