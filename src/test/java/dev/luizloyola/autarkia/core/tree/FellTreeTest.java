package dev.luizloyola.autarkia.core.tree;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.act.BreakState;
import dev.luizloyola.anima.core.brain.act.MoveFailure;
import dev.luizloyola.anima.core.brain.act.MoveState;
import dev.luizloyola.anima.core.brain.knowledge.BlockKind;
import dev.luizloyola.anima.core.brain.knowledge.FakeProbe;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.FakeContext;
import dev.luizloyola.anima.core.brain.task.TaskStatus;
import dev.luizloyola.anima.core.log.Entry;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The eighth chop so far: look at the ground beside the stump, take the cheapest side, walk to
 * it, clear that side's leaves as soon as the arm reaches them, plan the climb from the cell it
 * stands in, open the trunk and step in when the top is out of reach from there. Never ends.
 */
class FellTreeTest {

    private static final int BASE = FakeProbe.GROUND_Y + 1;
    private static final Pos ANCHOR = new Pos(0, BASE, 0);
    /** The south side's feet cell — nearest to a body standing to the south. */
    private static final Pos SOUTH = new Pos(0, BASE, 1);

    private final FakeContext ctx = new FakeContext();
    private final FellTree task = new FellTree(ANCHOR);

    /** A seven-log trunk: its top is out of reach from the ground beside it. */
    private void trunk() {
        trunk(7);
    }

    private void trunk(int logs) {
        for (int y = BASE; y < BASE + logs; y++) {
            ctx.percepts.blocks.set(0, y, 0, BlockKind.LOG);
        }
    }

    /** Ten blocks due south of the trunk. */
    private void standSouth() {
        ctx.percepts.position = new Pos(0, BASE, 10);
    }

    /** Water on north, east and west, so the south is the only side there is. */
    private void onlyTheSouth() {
        ctx.percepts.blocks.set(0, BASE - 1, -1, BlockKind.WATER);
        ctx.percepts.blocks.set(1, BASE, 0, BlockKind.WATER);
        ctx.percepts.blocks.set(-1, BASE, 0, BlockKind.WATER);
    }

    /** A block of ground on every side of the stump, so each side reads "up 1". */
    private void raisedGround() {
        for (Pos cell : List.of(new Pos(0, BASE, -1), new Pos(1, BASE, 0), new Pos(0, BASE, 1),
                new Pos(-1, BASE, 0))) {
            ctx.percepts.blocks.set(cell.x(), cell.y(), cell.z(), BlockKind.OTHER);
        }
    }

    /** The ground a block lower on every side of the stump, so each side reads "down 1". */
    private void sunkenGround() {
        for (Pos cell : List.of(new Pos(0, BASE - 1, -1), new Pos(1, BASE - 1, 0),
                new Pos(0, BASE - 1, 1), new Pos(-1, BASE - 1, 0))) {
            ctx.percepts.blocks.set(cell.x(), cell.y(), cell.z(), BlockKind.AIR);
        }
    }

    /** The legs report the walk done with the body at {@code feet}. */
    private void arriveAt(Pos feet) {
        ctx.mover.setState(MoveState.ARRIVED);
        ctx.percepts.position = feet;
        task.tick(ctx);
        ctx.mover.setState(MoveState.IDLE);
    }

    private Pos lastOrder() {
        return new Pos(ctx.mover.lastX, ctx.mover.lastY, ctx.mover.lastZ);
    }

    private List<String> said(String prefix) {
        return ctx.journalService.recent(ctx.journal().id(), Integer.MAX_VALUE).stream()
                .filter(entry -> entry.event().equals("chop"))
                .map(Entry::detail)
                .filter(detail -> detail.startsWith(prefix))
                .toList();
    }

    // ── looking ──────────────────────────────────────────────────────────────────────────────

    @Test
    void looksOnTheFirstTickAndKeepsRunning() {
        trunk();

        assertEquals(TaskStatus.RUNNING, task.tick(ctx));
        Approach approach = task.approach().orElseThrow();
        assertEquals(4, approach.sides().size());
        assertEquals(4, approach.approachable());
        for (int i = 0; i < 3 * FellTree.RESURVEY_TICKS; i++) {
            assertEquals(TaskStatus.RUNNING, task.tick(ctx), "nothing ends it yet");
        }
        assertTrue(task.describe().endsWith("N open (0) · E open (0) · S open (0) · W open (0)"),
                "the readout carries the verdicts: " + task.describe());
    }

    @Test
    void saysWhatItReadOnceUntilTheGroundChanges() {
        trunk();
        for (int i = 0; i < FellTree.RESURVEY_TICKS + 1; i++) {
            task.tick(ctx);
        }
        assertEquals(List.of("approach — N open (0) · E open (0) · S open (0) · W open (0)"),
                said("approach"), "a re-read that says the same thing says nothing");

        ctx.percepts.blocks.set(1, BASE, 0, BlockKind.OTHER);
        for (int i = 0; i < FellTree.RESURVEY_TICKS; i++) {
            task.tick(ctx);
        }
        assertEquals(2, said("approach").size());
        assertEquals("approach — N open (0) · E up 1 (2) · S open (0) · W open (0)",
                said("approach").get(1));
    }

    // ── walking ──────────────────────────────────────────────────────────────────────────────

    @Test
    void walksToTheCheapestSide() {
        trunk();
        standSouth();

        task.tick(ctx);
        assertEquals(1, ctx.mover.moveToCalls);
        assertEquals(SOUTH, new Pos(ctx.mover.lastX, ctx.mover.lastY, ctx.mover.lastZ),
                "the near side's feet cell");
        assertEquals(List.of("going S — open (0)"), said("going"));
        assertTrue(task.describe().contains("walking to S (0)"), task.describe());
        assertEquals(SOUTH, task.chosen().orElseThrow());

        ctx.mover.setState(MoveState.MOVING);
        for (int i = 0; i < 2 * FellTree.RESURVEY_TICKS; i++) {
            task.tick(ctx);
        }
        assertEquals(1, ctx.mover.moveToCalls, "one order per walk, not one per tick");
    }

    @Test
    void standsThereOnceArrived() {
        trunk();
        standSouth();
        task.tick(ctx);
        ctx.mover.setState(MoveState.MOVING);
        task.tick(ctx);

        ctx.mover.setState(MoveState.ARRIVED);
        ctx.percepts.position = SOUTH;
        assertEquals(TaskStatus.RUNNING, task.tick(ctx));
        assertEquals("at the tree, S side", task.phase());
        assertEquals(List.of("plan — open 2 · step in · 4 to break · no rise · then 1 from the ground"),
                said("plan"));

        ctx.mover.setState(MoveState.IDLE); // the legs rest after arriving, as they do
        task.tick(ctx);
        assertEquals("opening the trunk", task.phase());
        assertEquals(1, ctx.mover.moveToCalls, "arrived is arrived; no shuffling");
    }

    @Test
    void opensTheTrunkAndStepsInWhenTheTopIsOutOfReach() {
        trunk();
        standSouth();
        task.tick(ctx);
        ctx.mover.setState(MoveState.ARRIVED);
        ctx.percepts.position = SOUTH;
        task.tick(ctx);
        ctx.mover.setState(MoveState.IDLE);

        Pos second = new Pos(0, BASE + 1, 0);
        Pos third = new Pos(0, BASE + 2, 0);
        task.tick(ctx);
        assertEquals(List.of(second), ctx.breaker.targets, "the log above the base first");
        ctx.breaker.state = BreakState.FINISHED;
        ctx.percepts.blocks.clear(second.x(), second.y(), second.z());
        task.tick(ctx);
        assertEquals(List.of(second, third), ctx.breaker.targets);
        assertEquals(List.of("broke a log at (0, 65, 0)"), said("broke"));
        ctx.breaker.state = BreakState.FINISHED;
        ctx.percepts.blocks.clear(third.x(), third.y(), third.z());
        task.tick(ctx);
        assertEquals("stepping in", task.phase());
        assertEquals(2, ctx.mover.moveToCalls);
        assertEquals(second, new Pos(ctx.mover.lastX, ctx.mover.lastY, ctx.mover.lastZ),
                "onto the base log: feet one up, in the trunk's column");

        ctx.mover.setState(MoveState.ARRIVED);
        ctx.percepts.position = second;
        task.tick(ctx);
        assertEquals("in the trunk — open 2 · step in · 4 to break · no rise · then 1 from the ground", task.phase());
        assertEquals(List.of("in the trunk — open 2 · step in · 4 to break · no rise · then 1 from the ground"), said("in the"));

        ctx.mover.setState(MoveState.IDLE);
        for (int i = 0; i < 3 * FellTree.RESURVEY_TICKS; i++) {
            assertEquals(TaskStatus.RUNNING, task.tick(ctx));
        }
        assertEquals(2, ctx.breaker.targets.size(), "the plan is planned, not carried out");
        assertEquals(2, ctx.mover.moveToCalls);
    }

    @Test
    void aShortTreeIsPlannedFromBesideAndNobodyStepsIn() {
        trunk(4);
        standSouth();
        task.tick(ctx);
        ctx.mover.setState(MoveState.ARRIVED);
        ctx.percepts.position = SOUTH;
        task.tick(ctx);
        ctx.mover.setState(MoveState.IDLE);
        task.tick(ctx);

        assertEquals("at the tree — 4 to break from beside", task.phase());
        assertTrue(ctx.breaker.targets.isEmpty(), "nothing broken yet");
        assertEquals(1, ctx.mover.moveToCalls);
    }

    @Test
    void keepsItsSideWhileItStaysApproachable() {
        trunk();
        standSouth();
        task.tick(ctx);
        assertEquals(SOUTH, task.chosen().orElseThrow());

        // Now level with the trunk to the east: E is nearest, but S was taken and still stands.
        ctx.percepts.position = new Pos(10, BASE, 0);
        for (int i = 0; i < FellTree.RESURVEY_TICKS + 1; i++) {
            task.tick(ctx);
        }
        assertEquals(SOUTH, task.chosen().orElseThrow(), "selection is commitment");
        assertEquals(1, said("going").size());

        // Until it no longer is: water under it, and the body re-picks.
        ctx.percepts.blocks.set(0, BASE - 1, 1, BlockKind.WATER);
        for (int i = 0; i < FellTree.RESURVEY_TICKS; i++) {
            task.tick(ctx);
        }
        assertEquals(new Pos(1, BASE, 0), task.chosen().orElseThrow(), "the east side, nearest now");
        assertEquals(2, said("going").size());
        assertEquals(2, ctx.mover.moveToCalls, "a new side is a new walk");
    }

    @Test
    void retriesAFailedWalkAfterAWhile() {
        trunk();
        standSouth();
        task.tick(ctx);
        ctx.mover.setState(MoveState.FAILED);
        ctx.mover.setFailure(MoveFailure.UNREACHABLE);

        task.tick(ctx);
        assertEquals(List.of("walk to (0, 64, 1) failed — unreachable; trying again in 2s"),
                said("walk"));
        assertEquals(1, ctx.mover.moveToCalls, "not re-ordered on the spot");
        for (int i = 0; i < FellTree.WALK_RETRY_TICKS - 1; i++) {
            task.tick(ctx);
        }
        assertEquals(1, ctx.mover.moveToCalls);
        task.tick(ctx);
        assertEquals(2, ctx.mover.moveToCalls, "ordered again once the wait is up");
        assertEquals(1, said("walk").size(), "one line per failure, not per tick");
    }

    // ── clearing ─────────────────────────────────────────────────────────────────────────────

    @Test
    void clearsOnlyItsSidesLeavesAndStartsMidWalk() {
        trunk();
        standSouth();
        onlyTheSouth();
        Pos feetLeaf = new Pos(0, BASE, 1);
        Pos headLeaf = new Pos(0, BASE + 1, 1);
        ctx.percepts.blocks.set(feetLeaf.x(), feetLeaf.y(), feetLeaf.z(), BlockKind.LEAVES);
        ctx.percepts.blocks.set(headLeaf.x(), headLeaf.y(), headLeaf.z(), BlockKind.LEAVES);
        // A leaf that is not in the way, on the far side.
        ctx.percepts.blocks.set(0, BASE, -1, BlockKind.LEAVES);

        task.tick(ctx);
        assertEquals(1, ctx.mover.moveToCalls, "the walk is ordered onto the leaves anyway");
        assertEquals(1, ctx.breaker.targets.size(), "and the arm starts before the legs finish");
        assertTrue(List.of(feetLeaf, headLeaf).contains(ctx.breaker.target));
        assertTrue(task.phase().startsWith("walking to S"), task.phase());

        // The first comes down; the second is begun on the very next tick.
        Pos first = ctx.breaker.target;
        ctx.breaker.state = BreakState.FINISHED;
        ctx.percepts.blocks.clear(first.x(), first.y(), first.z());
        ctx.mover.setState(MoveState.MOVING);
        task.tick(ctx);
        assertEquals(2, ctx.breaker.targets.size());
        assertFalse(ctx.breaker.target.equals(first), "the other one");
        assertEquals(List.of("cleared a leaf at " + at(first)), said("cleared"));

        Pos second = ctx.breaker.target;
        ctx.breaker.state = BreakState.FINISHED;
        ctx.percepts.blocks.clear(second.x(), second.y(), second.z());
        task.tick(ctx);
        task.tick(ctx);
        assertEquals(2, ctx.breaker.targets.size(), "the far side's leaf is nobody's business");

        ctx.mover.setState(MoveState.ARRIVED);
        ctx.percepts.position = feetLeaf;
        task.tick(ctx);
        assertEquals("at the tree, S side", task.phase());
        assertEquals(2, ctx.breaker.targets.size(), "the plan comes first; the trunk waits a tick");
    }

    @Test
    void aLeafOutOfReachWaitsForTheWalk() {
        trunk();
        standSouth();
        onlyTheSouth();
        Pos leaf = new Pos(0, BASE, 1);
        ctx.percepts.blocks.set(leaf.x(), leaf.y(), leaf.z(), BlockKind.LEAVES);
        ctx.breaker.refuse.add(leaf);

        task.tick(ctx);
        ctx.mover.setState(MoveState.MOVING);
        task.tick(ctx);
        assertTrue(ctx.breaker.targets.isEmpty(), "refused: out of reach, nothing begun");
        assertTrue(ctx.breaker.begins >= 2, "asked again each tick, since the walk changes the answer");

        ctx.breaker.refuse.clear(); // walked into reach
        task.tick(ctx);
        assertEquals(List.of(leaf), ctx.breaker.targets);
    }

    @Test
    void aBreakThatDiesUnderTheArmIsTriedAgain() {
        trunk();
        standSouth();
        onlyTheSouth();
        Pos leaf = new Pos(0, BASE, 1);
        ctx.percepts.blocks.set(leaf.x(), leaf.y(), leaf.z(), BlockKind.LEAVES);

        task.tick(ctx);
        assertEquals(List.of(leaf), ctx.breaker.targets);
        ctx.breaker.state = BreakState.FAILED; // shoved out of reach mid-swing
        task.tick(ctx);
        assertEquals(List.of(leaf, leaf), ctx.breaker.targets, "still standing, so begun again");
        assertTrue(said("cleared").isEmpty(), "nothing came down");
    }

    // ── coming back ──────────────────────────────────────────────────────────────────────────

    /** The trunk as a body leaves it after opening: the base, two cells of air, four logs. */
    private void openedTrunk() {
        ctx.percepts.blocks.set(0, BASE, 0, BlockKind.LOG);
        for (int y = BASE + 3; y < BASE + 7; y++) {
            ctx.percepts.blocks.set(0, y, 0, BlockKind.LOG);
        }
    }

    @Test
    void aRestoredTaskCarriesOnFromItsStage() {
        openedTrunk();
        Pos stand = new Pos(0, BASE + 1, 0);
        Climb climb = Climb.plan(Climb.Arm.of(ctx.percepts), ANCHOR,
                Climb.column(ANCHOR, ctx.percepts.blocks, 2), ctx.percepts.blocks, SOUTH, 2);
        FellTree back = FellTree.restored(ANCHOR, FellTree.Stage.ENTER,
                java.util.Optional.of(new Pos(0, BASE, 1)), java.util.Optional.of(climb));
        ctx.percepts.position = SOUTH;

        back.tick(ctx);
        assertEquals("stepping in", back.phase());
        assertEquals(stand, new Pos(ctx.mover.lastX, ctx.mover.lastY, ctx.mover.lastZ),
                "straight back to stepping in — no survey walk, no re-plan");
        assertTrue(said("plan").isEmpty());

        ctx.mover.setState(MoveState.ARRIVED);
        ctx.percepts.position = stand;
        back.tick(ctx);
        assertEquals(FellTree.Stage.PLANNED, back.stage());
        assertEquals("in the trunk — open 0 · step in · 4 to break · no rise · then 1 from the ground", back.phase(),
                "the plan it was saved with, made from the trunk as it stood");
    }

    @Test
    void aRestoredStageWithoutAPlanStartsOver() {
        FellTree back = FellTree.restored(ANCHOR, FellTree.Stage.PLANNED,
                java.util.Optional.empty(), java.util.Optional.empty());
        assertEquals(FellTree.Stage.APPROACH, back.stage());
    }

    @Test
    void aFreshTaskAlreadyOnTheBaseLogPlansFromThere() {
        openedTrunk();
        ctx.percepts.position = new Pos(0, BASE + 1, 0);

        task.tick(ctx);
        assertEquals(0, ctx.mover.moveToCalls, "no walking out to walk back in");
        assertEquals(List.of("plan — open 0 · step in · 4 to break · no rise · then 1 from the ground"), said("plan"));
        assertEquals(FellTree.Stage.PLANNED, task.stage());
        assertEquals("in the trunk — open 0 · step in · 4 to break · no rise · then 1 from the ground", task.phase());
    }

    // ── the ground beside the stump ──────────────────────────────────────────────────────────

    @Test
    void onRaisedGroundItStandsOnTheSecondLogAndKeepsTheLowWoodForLast() {
        trunk(9);
        raisedGround();
        standSouth();
        task.tick(ctx);
        Pos feet = new Pos(0, BASE + 1, 1);
        assertEquals(feet, lastOrder(), "up onto the ground beside the stump");
        arriveAt(feet);

        Pos third = new Pos(0, BASE + 2, 0);
        Pos fourth = new Pos(0, BASE + 3, 0);
        task.tick(ctx);
        assertEquals(List.of(third), ctx.breaker.targets,
                "the trunk opens above the log at the body's own height, not above the stump");
        ctx.breaker.state = BreakState.FINISHED;
        ctx.percepts.blocks.clear(third.x(), third.y(), third.z());
        task.tick(ctx);
        assertEquals(List.of(third, fourth), ctx.breaker.targets);
        ctx.breaker.state = BreakState.FINISHED;
        ctx.percepts.blocks.clear(fourth.x(), fourth.y(), fourth.z());
        task.tick(ctx);
        assertEquals("stepping in", task.phase());
        assertEquals(third, lastOrder(), "onto the second log, never into it");

        arriveAt(third);
        assertEquals("in the trunk — open 2 · step in · 5 to break · 1 rise · then 2 from the ground",
                task.phase());
        assertEquals(List.of(new Pos(0, BASE + 1, 0), ANCHOR), task.climb().orElseThrow().last(),
                "the log underfoot and the stump under it wait for the ground");
    }

    @Test
    void onSunkenGroundItStepsUpOntoTheDirtAndOpensTheStumpItself() {
        trunk();
        sunkenGround();
        standSouth();
        task.tick(ctx);
        Pos feet = new Pos(0, BASE - 1, 1);
        assertEquals(feet, lastOrder(), "down onto the ground beside the stump");
        arriveAt(feet);

        Pos second = new Pos(0, BASE + 1, 0);
        task.tick(ctx);
        assertEquals(List.of(ANCHOR), ctx.breaker.targets, "the stump is in the body's way now");
        ctx.breaker.state = BreakState.FINISHED;
        ctx.percepts.blocks.clear(ANCHOR.x(), ANCHOR.y(), ANCHOR.z());
        task.tick(ctx);
        assertEquals(List.of(ANCHOR, second), ctx.breaker.targets);
        ctx.breaker.state = BreakState.FINISHED;
        ctx.percepts.blocks.clear(second.x(), second.y(), second.z());
        task.tick(ctx);
        assertEquals("stepping in", task.phase());
        assertEquals(ANCHOR, lastOrder(), "feet where the stump was, on the dirt that held it");

        arriveAt(ANCHOR);
        assertEquals("in the trunk — open 2 · step in · 5 to break · 1 rise", task.phase(),
                "the dirt is not the tree's: nothing waits for the end");
    }

    @Test
    void aLeafInTheTrunksWayIsClearedOnTheWayIn() {
        trunk();
        Pos leaf = new Pos(0, BASE + 2, 0);
        ctx.percepts.blocks.set(leaf.x(), leaf.y(), leaf.z(), BlockKind.LEAVES);
        standSouth();
        task.tick(ctx);
        arriveAt(SOUTH);

        Pos second = new Pos(0, BASE + 1, 0);
        task.tick(ctx);
        assertEquals(List.of(second), ctx.breaker.targets);
        ctx.breaker.state = BreakState.FINISHED;
        ctx.percepts.blocks.clear(second.x(), second.y(), second.z());
        task.tick(ctx);
        assertEquals(List.of(second, leaf), ctx.breaker.targets, "the leaf is in the way too");
        ctx.breaker.state = BreakState.FINISHED;
        ctx.percepts.blocks.clear(leaf.x(), leaf.y(), leaf.z());
        task.tick(ctx);
        assertEquals(List.of("cleared a leaf at " + at(leaf)), said("cleared"));
        assertEquals("stepping in", task.phase());
    }

    // ── cancel ───────────────────────────────────────────────────────────────────────────────

    @Test
    void cancelReleasesTheLegsAndTheArm() {
        task.cancel(ctx); // before any tick: harmless
        trunk();
        standSouth();
        onlyTheSouth();
        ctx.percepts.blocks.set(0, BASE, 1, BlockKind.LEAVES);
        task.tick(ctx);
        assertEquals(1, ctx.breaker.targets.size());

        task.cancel(ctx);
        assertEquals(BreakState.IDLE, ctx.breaker.state);
        assertEquals(MoveState.IDLE, ctx.mover.state());
        assertTrue(ctx.mover.stopCalls >= 1);
    }

    private static String at(Pos cell) {
        return "(" + cell.x() + ", " + cell.y() + ", " + cell.z() + ")";
    }
}
