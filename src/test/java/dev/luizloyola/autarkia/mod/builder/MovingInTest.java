package dev.luizloyola.autarkia.mod.builder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.autarkia.core.bp.Footprint;
import dev.luizloyola.autarkia.core.bp.Placement;
import dev.luizloyola.autarkia.core.builder.Structure;
import dev.luizloyola.autarkia.core.direction.ShelterLine;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MovingInTest {

    /** Clara's: 16 stacks outside, five empty chests grown in the house, 12 slots kept free. */
    @Test
    void aStoreLeftOutsideFoldsInOnceTheHouseHasRoomForIt() {
        assertEquals(List.of(0), MovingIn.folding(List.of(16), 5 * 27 - 12));
    }

    @Test
    void aStoreTheHouseHasNoRoomForStaysOut() {
        assertEquals(List.of(), MovingIn.folding(List.of(16), 15));
    }

    @Test
    void storesFoldInOrderWhileTheRoomLasts() {
        assertEquals(List.of(0, 2), MovingIn.folding(List.of(10, 20, 5), 18));
    }

    @Test
    void aHouseBesideTheFirstNightHutIsStillTheFirstToStand() {
        Structure hut = building(1, ShelterLine.BLUEPRINT);
        Structure house = building(2, "autarkia:basic_wooden_house");
        assertTrue(MovingIn.first(List.of(hut, house), house), "the starter base was never moved in");
        assertFalse(MovingIn.first(List.of(building(3, "autarkia:basic_wooden_house"), house), house));
    }

    private static Structure building(int id, String blueprint) {
        return new Structure(new UUID(5, id), blueprint, 1, Map.of(), Map.of(), new Pos(id * 20, 64, 0),
                Placement.AS_DRAWN, new Footprint(id * 20 - 2, -2, id * 20 + 2, 2),
                new Footprint(id * 20 - 4, -4, id * 20 + 4, 4), Structure.Phase.BUILT, 0L, "");
    }
}
