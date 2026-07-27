package dev.luizloyola.autarkia.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.luizloyola.autarkia.core.brain.sense.Pos;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Drop clustering: two piles are two flocks; they walk to the nearer pile's middle. */
class FlocksTest {

    @Test
    void separatePilesAreSeparateFlocks() {
        List<Pos> drops = List.of(
                new Pos(0, 64, 0), new Pos(1, 64, 1), new Pos(2, 64, 0),   // pile A
                new Pos(10, 64, 10), new Pos(11, 64, 10));                  // pile B

        assertEquals(2, Flocks.count(drops));
        assertEquals(new Pos(1, 64, 0), Flocks.nearestCentroid(drops, new Pos(-2, 64, 0)),
                "pile A's centroid, since they stand west of it");
        assertEquals(new Pos(11, 64, 10), Flocks.nearestCentroid(drops, new Pos(14, 64, 10)),
                "pile B's centroid from the east");
    }

    @Test
    void noDropsNoFlocks() {
        assertEquals(0, Flocks.count(List.of()));
        assertNull(Flocks.nearestCentroid(List.of(), new Pos(0, 64, 0)));
    }
}
