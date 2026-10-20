package dev.luizloyola.autarkia.core.builder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.territory.ChunkKey;
import dev.luizloyola.autarkia.core.bp.Footprint;
import dev.luizloyola.autarkia.core.bp.Placement;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** A door is a house's when it is within the walls of a building that stands; what ground it waits on. */
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
        for (Structure.Phase phase : List.of(Structure.Phase.SITED, Structure.Phase.CLEARING,
                Structure.Phase.CLEARED, Structure.Phase.LEVELLED,
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

    /** The pad spans 0..11 by 0..12; with the flatten's ring that is chunks -1..1 each way. */
    @Test
    void itWaitsOnlyForTheTreesOnItsOwnGroundChunks() {
        Structure house = house(Structure.Phase.SITED);
        Set<ChunkKey> ground = house.groundChunks();
        assertEquals(9, ground.size());
        Set<ChunkKey> area = new HashSet<>(ground);
        area.add(new ChunkKey(ChunkKey.OVERWORLD, 5, 5));

        assertTrue(house.groundFelled(area, ground), "a chunk of the area off its ground is not waited on");
        Set<ChunkKey> one = new HashSet<>(ground);
        one.remove(new ChunkKey(ChunkKey.OVERWORLD, 0, 0));
        assertFalse(house.groundFelled(area, one));
        area.remove(new ChunkKey(ChunkKey.OVERWORLD, 0, 0));
        assertTrue(house.groundFelled(area, one), "a margin chunk the area does not hold is nobody's to fell");
    }

    @Test
    void itsPlantsArePulledUpTwoBlocksPastThePad() {
        assertEquals(new Footprint(-2, -2, 13, 14), house(Structure.Phase.SITED).plantsArea());
    }
}
