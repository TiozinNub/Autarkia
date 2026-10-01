package dev.luizloyola.autarkia.core.direction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.knowledge.Region;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.territory.ChunkKey;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** HOME without a yard: the area's middle, worked out when asked, and work matched by columns. */
class HomeTest {

    private static ChunkKey chunk(int x, int z) {
        return new ChunkKey(ChunkKey.OVERWORLD, x, z);
    }

    @Test
    void theMiddleIsTheChunkNearestTheMeanOfTheArea() {
        // An L: the mean is (2/3, 2/3), nearest the corner chunk, not either arm's end.
        assertEquals(Optional.of(chunk(0, 0)), Home.middle(Set.of(chunk(0, 0), chunk(2, 0), chunk(0, 2))));
        assertEquals(Optional.of(chunk(1, 0)), Home.middle(Set.of(chunk(0, 0), chunk(1, 0), chunk(2, 0))));
        assertTrue(Home.middle(Set.of()).isEmpty(), "no area, no middle");
    }

    @Test
    void workOnAChunkIsMatchedByItsColumnsAtAnyHeight() {
        ChunkKey east = chunk(1, 0);

        assertEquals(Optional.of(east), Home.chunkOf(Home.region(east, 64)));
        assertEquals(Optional.of(east), Home.chunkOf(Home.region(east, 90)),
                "posted at another height — the party's stores moved — it is still that chunk's");
        assertTrue(Home.chunkOf(new Region(new Pos(16, 50, 0), new Pos(30, 100, 15))).isEmpty(),
                "a box short of the chunk's columns is not its clearing");
    }

    @Test
    void theNextChunkToClearIsTheOneNearestWhereItIsAsked() {
        Home home = Home.fresh().withCleared(chunk(0, 0));
        List<ChunkKey> left = home.uncleared(List.of(chunk(0, 0), chunk(3, 0), chunk(1, 0)), new Pos(8, 64, 8));

        assertEquals(List.of(chunk(1, 0), chunk(3, 0)), left);
    }
}
