package dev.luizloyola.autarkia.core.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.knowledge.Region;
import dev.luizloyola.anima.core.brain.sense.Drop;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.GatherNearbyDrops;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A settler picks up what its own work let fall, and leaves what lies elsewhere. */
class GleanDropsTest {

    private static Drop sapling(int x, int z) {
        Pos at = new Pos(x, 64, z);
        return new Drop(at, "minecraft:oak_sapling", Region.of(at));
    }

    private static void beats(GleanDrops glean, BoardBrainContext ctx, int n) {
        for (int tick = 0; tick < n * GleanDrops.CHECK_INTERVAL; tick++) {
            glean.tick(ctx);
        }
    }

    @Test
    void itPostsForADropBesideItsWork() {
        BoardBrainContext ctx = new BoardBrainContext();
        ctx.workSpots.record(new Pos(0, 64, 0), 0);
        ctx.drops = List.of(sapling(3, -2));
        GleanDrops glean = new GleanDrops();

        beats(glean, ctx, 1);
        assertEquals(1, glean.open().size());
        var sweep = assertInstanceOf(GatherNearbyDrops.class, glean.open().get(0).root());
        assertTrue(sweep.nearWork());
    }

    @Test
    void aDropAwayFromItsWorkIsNotItsOwn() {
        BoardBrainContext ctx = new BoardBrainContext();
        ctx.workSpots.record(new Pos(0, 64, 0), 0);
        ctx.drops = List.of(sapling(20, 0));
        GleanDrops glean = new GleanDrops();

        beats(glean, ctx, 3);
        assertEquals(0, glean.open().size());
    }

    @Test
    void itIsWithdrawnWhenTheDropIsGoneBeforeAnybodyCame() {
        BoardBrainContext ctx = new BoardBrainContext();
        ctx.workSpots.record(new Pos(0, 64, 0), 0);
        ctx.drops = List.of(sapling(1, 1));
        GleanDrops glean = new GleanDrops();
        beats(glean, ctx, 1);

        ctx.drops = List.of();
        beats(glean, ctx, 1);
        assertEquals(0, glean.open().size());
    }
}
