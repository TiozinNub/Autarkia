package dev.luizloyola.autarkia.mod.builder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.territory.ChunkKey;
import dev.luizloyola.autarkia.core.board.ClearPlants;
import dev.luizloyola.autarkia.core.board.FellTrees;
import dev.luizloyola.autarkia.core.bp.Footprint;
import dev.luizloyola.autarkia.core.bp.Placement;
import dev.luizloyola.autarkia.core.builder.Structure;
import dev.luizloyola.autarkia.core.direction.Home;
import dev.luizloyola.autarkia.core.tree.TreeFelling;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * A sited building's own felling and plant clearing are round its pad, never over its whole chunks.
 * The phase walk itself reads the server's board and journals, and is flown.
 */
class StructuresTest {

    private static final Structure HOUSE = new Structure(new UUID(4, 2), "autarkia:basic_wooden_house", 3,
            Map.of(), Map.of(), new Pos(5, 64, 6), Placement.AS_DRAWN, new Footprint(1, 1, 9, 10),
            new Footprint(0, 0, 11, 12), Structure.Phase.SITED, 0L, "");

    @Test
    void theFellingIsThePadAndTheFlattensRingRoundIt() {
        FellTrees felling = Structures.felling(HOUSE, 2);
        assertEquals(Home.region(-8, -8, 19, 20, 64), felling.bounds());
        assertEquals(0.5, felling.priority(), "the building's bid, as the flatten after it");
        assertTrue(Structures.fells(felling, HOUSE));
        assertFalse(Structures.fells(new FellTrees(TreeFelling.INSTANCE,
                Home.region(new ChunkKey(ChunkKey.OVERWORLD, 0, 0), 64), 0.5, true, 2), HOUSE), "the area line's chunk");
        assertTrue(Structures.fells(new FellTrees(TreeFelling.INSTANCE, Home.region(-8, -8, 19, 20, 90), 0.5,
                true, 2), HOUSE), "read by its columns");
    }

    @Test
    void theClearingIsThePadAndTwoBlocksRoundIt() {
        ClearPlants plants = Structures.clearing(HOUSE, 2);
        assertEquals(Home.region(-2, -2, 13, 14, 64), plants.bounds());
        assertEquals(0.5, plants.priority(), "as the flatten after it bids");
        assertTrue(Structures.clears(plants, HOUSE));
    }

    @Test
    void aChunksClearingIsNotThePads() {
        assertFalse(Structures.clears(new ClearPlants(Home.region(new ChunkKey(ChunkKey.OVERWORLD, 0, 0), 64), 0.5, 2),
                HOUSE));
        Structure elsewhere = new Structure(new UUID(4, 3), HOUSE.blueprint(), 3, Map.of(), Map.of(),
                new Pos(37, 64, 6), Placement.AS_DRAWN, new Footprint(33, 1, 41, 10), new Footprint(32, 0, 43, 12),
                Structure.Phase.CLEARING, 0L, "");
        assertFalse(Structures.clears(Structures.clearing(elsewhere, 2), HOUSE), "another building's");
        assertTrue(Structures.clears(new ClearPlants(Home.region(-2, -2, 13, 14, 90), 0.5, 2), HOUSE),
                "read by its columns: the height follows the anchor");
    }
}
