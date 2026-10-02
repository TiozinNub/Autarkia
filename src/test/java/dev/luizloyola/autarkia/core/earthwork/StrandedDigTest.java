package dev.luizloyola.autarkia.core.earthwork;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.knowledge.BlockKind;
import dev.luizloyola.anima.core.brain.knowledge.FakeProbe;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.BlocksToCross;
import dev.luizloyola.anima.core.brain.task.FakeContext;
import dev.luizloyola.anima.core.brain.task.Method;
import dev.luizloyola.anima.core.brain.task.Producers;
import dev.luizloyola.anima.core.brain.task.TaskExecutor;
import dev.luizloyola.anima.core.brain.task.TaskStatus;
import dev.luizloyola.anima.core.nav.Gait;
import dev.luizloyola.anima.core.nav.HandsOff;
import dev.luizloyola.autarkia.core.board.Stock;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A walk stranded short of blocks digs them from the ground round it (Luiz, 2026-10-02) — the
 * average rule's ground only, and never for a settler's standing stack.
 */
class StrandedDigTest {

    private static final int G = FakeProbe.GROUND_Y;

    private final FakeContext ctx = new FakeContext();
    private final FakeProbe probe = ctx.percepts.blocks;
    private final Pos here = new Pos(0, G + 1, 0);

    @BeforeEach
    void setUp() {
        ctx.percepts.position = here;
        Stock.dirtBy(id -> id.equals("minecraft:dirt") || id.equals("minecraft:grass_block"));
        Stock.layableBy(id -> id.equals("minecraft:dirt"));
        BlocksToCross.layableBy(id -> id.equals("minecraft:dirt"));
        DigDirt.register();
    }

    @AfterEach
    void tearDown() {
        DigDirt.fenceBy((near, reach) -> HandsOff.NONE);
        Stock.dirtBy(id -> false);
        Stock.layableBy(id -> false);
        BlocksToCross.layableBy(id -> false);
    }

    /** Grass on the column's top at {@code height}, ground built up under it. */
    private void grass(int x, int z, int height) {
        for (int y = G + 1; y <= height; y++) {
            probe.set(x, y, z, BlockKind.OTHER);
        }
        probe.setId(x, height, z, "minecraft:grass_block");
    }

    /** The executor running a walk that stranded wanting three blocks, ticked once. */
    private TaskExecutor fetching() {
        TaskExecutor executor = new TaskExecutor();
        executor.run(new BlocksToCross(9, G + 1, 0, Gait.WALK, 3, false), ctx);
        executor.tick(ctx);
        return executor;
    }

    @Test
    void aMoundNearbyIsDugForTheBlocks() {
        grass(6, 0, G + 2);
        TaskExecutor executor = fetching();
        assertTrue(executor.isBusy());
        assertTrue(executor.describe().contains("dig dirt for blocks_to_cross"), executor.describe());
        assertEquals(1, ctx.mover.moveToCalls, "off to the mound");
        assertTrue(Math.abs(ctx.mover.lastX - 6) <= 1 && Math.abs(ctx.mover.lastZ) <= 1,
                "beside it: " + ctx.mover.lastX + ", " + ctx.mover.lastZ);
    }

    @Test
    void flatGroundIsNeverDugAndTheWalkGivesUp() {
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                grass(x, z, G);
            }
        }
        TaskExecutor executor = fetching();
        assertFalse(executor.isBusy());
        assertEquals(Optional.of(TaskStatus.FAILED), executor.lastStatus());
        assertEquals(0, ctx.mover.moveToCalls, "no walk, and no pit");
        assertTrue(ctx.breaker.targets.isEmpty());
    }

    @Test
    void settledGroundIsNeverDug() {
        grass(6, 0, G + 2);
        DigDirt.fenceBy((near, reach) -> HandsOff.columns(List.of(new int[] {-20, -20, 20, 20})));
        assertFalse(fetching().isBusy());
        assertEquals(0, ctx.mover.moveToCalls);
    }

    @Test
    void groundPastTheBodysSurroundingsIsNotLookedFor() {
        grass(DigDirt.NEAR + 4, 0, G + 2);
        assertFalse(fetching().isBusy(), "a mound across the country is not round where it stands");
    }

    @Test
    void aMoundItCouldNotReachLatelyIsLeft() {
        grass(6, 0, G + 2);
        fetching();
        Pos stand = new Pos(ctx.mover.lastX, ctx.mover.lastY, ctx.mover.lastZ);
        FakeContext again = ctx;
        again.unreached.strike(stand, again.percepts.time);
        TaskExecutor retry = new TaskExecutor();
        int walks = again.mover.moveToCalls;
        retry.run(new BlocksToCross(9, G + 1, 0, Gait.WALK, 3, false), again);
        retry.tick(again);
        assertEquals(walks, again.mover.moveToCalls, "across the gap it stranded on");
    }

    @Test
    void aStandingStackOfBridgingBlocksNeverDigs() {
        grass(6, 0, G + 2);
        for (Method way : Producers.forSpec(Stock.BRIDGING)) {
            assertFalse(way instanceof DigDirt, "a standing want would send every settler digging");
        }
        assertTrue(Producers.forSpec(BlocksToCross.SPEC).stream().anyMatch(way -> way instanceof DigDirt));
    }
}
