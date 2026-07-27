package dev.luizloyola.autarkia.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.autarkia.core.brain.Arbiter;
import dev.luizloyola.autarkia.core.brain.act.BreakState;
import dev.luizloyola.autarkia.core.brain.instinct.DescendInstinct;
import dev.luizloyola.autarkia.core.brain.knowledge.BlockKind;
import dev.luizloyola.autarkia.core.brain.sense.Pos;
import org.junit.jupiter.api.Test;

/**
 * The strand-recovery pair for towers whose building task died: {@link UnbuildPillar} clears the
 * ledger newest first, conceding unreachable cells rather than looping, and
 * {@link DescendInstinct} bids exactly while a ledger stands.
 */
class UnbuildPillarTest {

    private final FakeContext ctx = new FakeContext();

    private void tower(Pos... cells) {
        for (Pos cell : cells) {
            ctx.percepts.blocks.set(cell.x(), cell.y(), cell.z(), BlockKind.LOG);
            ctx.scaffolder.placed.push(cell);
        }
    }

    private TaskStatus drive(UnbuildPillar task, int maxTicks) {
        TaskStatus status = TaskStatus.RUNNING;
        for (int i = 0; i < maxTicks && status == TaskStatus.RUNNING; i++) {
            status = task.tick(ctx);
            if (ctx.breaker.state == BreakState.BREAKING) {
                Pos t = ctx.breaker.target;
                ctx.percepts.blocks.clear(t.x(), t.y(), t.z());
                ctx.breaker.state = BreakState.FINISHED;
            }
        }
        return status;
    }

    @Test
    void unbuildsTheLedgerNewestFirst() {
        Pos first = new Pos(5, 64, 5);
        Pos second = new Pos(5, 65, 5);
        tower(first, second);
        ctx.percepts.position = new Pos(5, 66, 5); // standing on top, like a stranded climber

        TaskStatus status = drive(new UnbuildPillar(), 50);

        assertEquals(TaskStatus.SUCCESS, status);
        assertEquals(java.util.List.of(second, first), ctx.breaker.targets,
                "broken underfoot, newest cell first — the player's way down a nerd-pole");
        assertTrue(ctx.scaffolder.placed.isEmpty());
    }

    @Test
    void anUnreachableCellIsLeftStandingNotLoopedOn() {
        Pos reachable = new Pos(5, 64, 5);
        Pos marooned = new Pos(9, 70, 9); // a stray segment they can't get back to
        tower(reachable, marooned);
        ctx.breaker.refuse.add(marooned); // out of arm's reach, and the walk won't help (IDLE mover)

        TaskStatus status = drive(new UnbuildPillar(), 50);

        assertEquals(TaskStatus.SUCCESS, status, "one orphan block beats looping forever");
        assertTrue(ctx.scaffolder.placed.isEmpty(), "struck from the ledger either way");
        assertEquals(java.util.List.of(reachable), ctx.breaker.targets,
                "only the reachable cell was actually broken");
    }

    @Test
    void anEmptyLedgerIsAnInstantSuccess() {
        assertEquals(TaskStatus.SUCCESS, new UnbuildPillar().tick(ctx));
    }

    /**
     * A cell below a standing higher cell of the same column must never be broken; an unreachable
     * column is conceded WHOLE. Breaking what it could reach and skipping what it could not ate a
     * tower's BOTTOM from under its standing top, leaving gapped log-air-log trunks.
     */
    @Test
    void aTowersBottomIsNeverEatenFromUnderItsStandingTop() {
        Pos base = new Pos(5, 64, 5);
        Pos mid = new Pos(5, 65, 5);
        Pos top = new Pos(5, 66, 5);
        tower(base, mid, top);
        ctx.percepts.position = new Pos(20, 64, 20); // they walked away; the mover won't help
        ctx.breaker.refuse.add(top); // the top is out of reach — the lower cells are not

        TaskStatus status = drive(new UnbuildPillar(), 60);

        assertEquals(TaskStatus.SUCCESS, status);
        assertTrue(ctx.scaffolder.placed.isEmpty(), "the column is struck whole either way");
        assertTrue(!ctx.breaker.targets.contains(mid) && !ctx.breaker.targets.contains(base),
                "no cell below a standing top is broken — a whole tower beats a floating one");
        assertEquals(BlockKind.LOG, ctx.percepts.blocks.at(5, 64, 5),
                "the tower stands intact, not gap-eaten into a floater");
        assertEquals(BlockKind.LOG, ctx.percepts.blocks.at(5, 66, 5));
    }

    /**
     * A pillar holding anything up is conceded whole: it stands in the trunk's own column, so an
     * interrupted climb leaves real trunk logs on its ledgered cells and pulling those out gives
     * the gapped log-air-log column. Remnant plus pillar reads as one grounded trunk for the next
     * chop.
     */
    @Test
    void aPillarHoldingRealWoodAboveIsLeftWholeForTheChopper() {
        Pos base = new Pos(5, 64, 5);
        Pos top = new Pos(5, 65, 5);
        tower(base, top);
        ctx.percepts.blocks.set(5, 66, 5, BlockKind.LOG); // the remnant trunk — not theirs
        ctx.percepts.position = new Pos(7, 64, 5); // right beside it: every cell in easy reach

        TaskStatus status = drive(new UnbuildPillar(), 60);

        assertEquals(TaskStatus.SUCCESS, status);
        assertTrue(ctx.scaffolder.placed.isEmpty(), "conceded — struck from the ledger whole");
        assertTrue(ctx.breaker.targets.isEmpty(),
                "not one swing: breaking their cells would float the remnant sitting on them");
        assertEquals(BlockKind.LOG, ctx.percepts.blocks.at(5, 64, 5));
        assertEquals(BlockKind.LOG, ctx.percepts.blocks.at(5, 65, 5));
        assertEquals(BlockKind.LOG, ctx.percepts.blocks.at(5, 66, 5),
                "remnant + pillar stand as one grounded trunk for the next fell");
    }

    /**
     * The recovery climb fires only for a top well above them; a cell height cannot help with is
     * conceded whole. A refused same-height cell had the recovery rung place a step that became
     * the ledger's new highest cell, which the top-down rule then broke back out from under them
     * — 185 cycles in one log.
     */
    @Test
    void theRecoveryClimbNeverFiresForACellAtTheirOwnHeight() {
        Pos stray = new Pos(7, 64, 5); // their height, two out — no pillar makes this closer
        tower(stray);
        ctx.percepts.position = new Pos(5, 64, 5);
        ctx.breaker.refuse.add(stray); // the arm refuses it (hemmed), and the mover won't move
        ctx.percepts.inventory.add(dev.luizloyola.autarkia.core.inv.ItemStack.of(
                "minecraft:oak_log", 8, 64)); // blocks available — only the guard decides

        TaskStatus status = drive(new UnbuildPillar(), 60);

        assertEquals(TaskStatus.SUCCESS, status);
        assertEquals(0, ctx.scaffolder.ups,
                "no recovery step for a cell at their own height — that loop placed forever");
        assertTrue(ctx.scaffolder.placed.isEmpty(), "conceded whole instead");
        assertEquals(BlockKind.LOG, ctx.percepts.blocks.at(7, 64, 5), "left standing, conceded");
    }

    /**
     * Too tall to break from the ground, so the tower is RE-CLIMBED on their own carried logs;
     * the recovery steps ledger their cells, so everything built getting up comes back down too.
     */
    @Test
    void theyReclimbForATallTowerTheyWalkedAwayFrom() {
        Pos base = new Pos(5, 64, 5);
        Pos mid = new Pos(5, 65, 5);
        Pos top = new Pos(5, 66, 5);
        tower(base, mid, top);
        ctx.percepts.position = new Pos(7, 64, 5); // beside it, on the ground
        ctx.breaker.refuse.add(top); // out of reach until height has been gained
        ctx.percepts.inventory.add(dev.luizloyola.autarkia.core.inv.ItemStack.of(
                "minecraft:oak_log", 4, 64));

        UnbuildPillar task = new UnbuildPillar();
        TaskStatus status = TaskStatus.RUNNING;
        for (int i = 0; i < 80 && status == TaskStatus.RUNNING; i++) {
            status = task.tick(ctx);
            if (ctx.scaffolder.state == dev.luizloyola.autarkia.core.brain.act.ScaffoldState.RISING) {
                // Play the body.
                Pos feet = ctx.percepts.position;
                ctx.percepts.blocks.set(feet.x(), feet.y(), feet.z(), BlockKind.LOG);
                ctx.scaffolder.placed.push(feet);
                ctx.percepts.position = new Pos(feet.x(), feet.y() + 1, feet.z());
                ctx.scaffolder.state = dev.luizloyola.autarkia.core.brain.act.ScaffoldState.RISEN;
                ctx.breaker.refuse.remove(top); // height gained — the top is in reach now
            }
            if (ctx.breaker.state == BreakState.BREAKING) {
                Pos t = ctx.breaker.target;
                ctx.percepts.blocks.clear(t.x(), t.y(), t.z());
                ctx.breaker.state = BreakState.FINISHED;
            }
        }

        assertEquals(TaskStatus.SUCCESS, status);
        assertTrue(ctx.scaffolder.ups >= 1, "they climbed back up for the tower they left");
        assertTrue(ctx.scaffolder.placed.isEmpty(), "ledger clean — recovery cells included");
        assertEquals(BlockKind.AIR, ctx.percepts.blocks.at(5, 66, 5), "the tower came down");
        assertEquals(BlockKind.AIR, ctx.percepts.blocks.at(5, 64, 5));
        assertEquals(BlockKind.AIR, ctx.percepts.blocks.at(7, 64, 5),
                "and so did every recovery step they placed getting to it");
    }

    @Test
    void theDescendInstinctBidsExactlyWhileALedgerStands() {
        DescendInstinct instinct = new DescendInstinct();
        assertEquals(0.0, instinct.pressure(ctx), "no tower, no want");
        tower(new Pos(5, 64, 5));
        assertEquals(DescendInstinct.strandedPressure(), instinct.pressure(ctx));
        assertTrue(instinct.pressure(ctx) < Arbiter.preempt(),
                "below the arbiter's PREEMPT bar: a chop legitimately mid-climb is never cut");
        assertTrue(instinct.root(ctx) instanceof UnbuildPillar);
    }
}
