package dev.luizloyola.autarkia.core.tree;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.act.BreakState;
import dev.luizloyola.anima.core.brain.act.MoveFailure;
import dev.luizloyola.anima.core.brain.act.MoveState;
import dev.luizloyola.anima.core.brain.act.RiseState;
import dev.luizloyola.anima.core.brain.knowledge.BlockKind;
import dev.luizloyola.anima.core.brain.knowledge.FakeProbe;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.FakeContext;
import dev.luizloyola.anima.core.brain.task.TaskStatus;
import dev.luizloyola.anima.core.inv.ItemStack;
import dev.luizloyola.anima.core.log.Entry;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The eighth chop so far: look at the ground beside the stump, take the cheapest side, walk to
 * it, clear that side's leaves as soon as the arm reaches them, plan the climb from the cell it
 * stands in, open the trunk and get in when the top is out of reach from there, rise to the
 * height the top demands, clear everything above, come down breaking underfoot, and take the last
 * log from outside. Ends with the count, or with the one reason it could not.
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
            ctx.percepts.blocks.setId(0, y, 0, "minecraft:birch_log");
        }
    }

    /** The pack: this tree's own logs, to rise on. */
    private void pack(int logs) {
        ctx.percepts.inventory.add(new ItemStack("minecraft:birch_log", logs, 64, ""));
    }

    /**
     * A little world that answers the fakes the way the real one would, one tick behind: a swing
     * begun lands and the block is gone, a walk ordered arrives, a rise ordered lands one higher
     * on a log from the pack, and nothing under the feet drops the body one.
     */
    private TaskStatus drive(FellTree task, int maxTicks) {
        TaskStatus status = TaskStatus.RUNNING;
        for (int i = 0; i < maxTicks && status == TaskStatus.RUNNING; i++) {
            int walks = ctx.mover.moveToCalls;
            status = task.tick(ctx);
            if (ctx.breaker.state == BreakState.BREAKING) {
                Pos t = ctx.breaker.target;
                ctx.percepts.blocks.clear(t.x(), t.y(), t.z());
                ctx.breaker.state = BreakState.FINISHED;
            }
            if (ctx.mover.moveToCalls > walks) {
                ctx.percepts.position = new Pos(ctx.mover.lastX, ctx.mover.lastY, ctx.mover.lastZ);
                ctx.mover.setState(MoveState.ARRIVED);
            }
            if (ctx.riser.state == RiseState.RISING) {
                Pos p = ctx.percepts.position;
                ctx.percepts.blocks.set(p.x(), p.y(), p.z(), BlockKind.LOG);
                ctx.percepts.inventory.remove(ctx.riser.lastItem, 1);
                ctx.percepts.position = new Pos(p.x(), p.y() + 1, p.z());
                ctx.riser.state = RiseState.RISEN;
            }
            Pos p = ctx.percepts.position;
            while (ctx.percepts.blocks.at(p.x(), p.y() - 1, p.z()) == BlockKind.AIR) {
                p = new Pos(p.x(), p.y() - 1, p.z());
                ctx.percepts.position = p;
            }
        }
        return status;
    }

    private TaskStatus drive(int maxTicks) {
        return drive(task, maxTicks);
    }

    private void assertColumnGone(int logs) {
        for (int y = BASE; y < BASE + logs; y++) {
            assertEquals(BlockKind.AIR, ctx.percepts.blocks.at(0, y, 0), "at y " + y);
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
        assertEquals(List.of("plan — open 2 · step in · no rise · 4 above · 1 underfoot"),
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
        assertEquals("in the trunk — open 2 · step in · no rise · 4 above · 1 underfoot", task.phase());
        assertEquals(List.of("in the trunk — open 2 · step in · no rise · 4 above · 1 underfoot"),
                said("in the"));

        ctx.mover.setState(MoveState.IDLE);
        task.tick(ctx);
        assertEquals("clearing above (4 left)", task.phase(), "no rise to make: straight to the top");
        assertEquals(new Pos(0, BASE + 3, 0), ctx.breaker.target, "the lowest log over the head");
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
        assertEquals(List.of("plan — 4 to break from beside"), said("plan"));
        assertEquals("at the tree, S side", task.phase());

        ctx.mover.setState(MoveState.IDLE);
        task.tick(ctx);
        assertEquals("clearing above (3 left)", task.phase(), "from where it stands: no opening");
        assertEquals(new Pos(0, BASE + 1, 0), ctx.breaker.target);
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
                Climb.column(ANCHOR, ctx.percepts.blocks, 2), ctx.percepts.blocks, SOUTH, true, 2);
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
        assertEquals("in the trunk — open 0 · step in · no rise · 4 above · 1 underfoot", back.phase(),
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
        assertEquals(List.of("plan — open 0 · step in · no rise · 4 above · 1 underfoot"), said("plan"));
        assertEquals(FellTree.Stage.PLANNED, task.stage());
        assertEquals("in the trunk — open 0 · step in · no rise · 4 above · 1 underfoot", task.phase());
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
        assertEquals("in the trunk — open 2 · step in · 1 rise · 5 above · 1 underfoot · then 1 from outside",
                task.phase());
        assertEquals(List.of(new Pos(0, BASE + 1, 0)), task.climb().orElseThrow().under(),
                "the log underfoot comes out on the way down");
        assertEquals(List.of(ANCHOR), task.climb().orElseThrow().last(), "and the stump from outside");
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
        assertEquals("in the trunk — open 2 · step in · 1 rise · 5 above", task.phase(),
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

    // ── no room to hop ───────────────────────────────────────────────────────────────────────

    @Test
    void aRoofTwoOverTheFeetMeansDiggingInRatherThanHoppingUp() {
        trunk();
        standSouth();
        onlyTheSouth();
        ctx.percepts.blocks.set(0, BASE + 2, 1, BlockKind.OTHER); // room for a body, not a hop
        task.tick(ctx);
        assertEquals(List.of("going S — open, low (3)"), said("going"));
        arriveAt(SOUTH);

        Pos second = new Pos(0, BASE + 1, 0);
        task.tick(ctx);
        assertEquals(List.of(ANCHOR), ctx.breaker.targets, "the stump, at its own feet height, first");
        ctx.breaker.state = BreakState.FINISHED;
        ctx.percepts.blocks.clear(ANCHOR.x(), ANCHOR.y(), ANCHOR.z());
        task.tick(ctx);
        assertEquals(List.of(ANCHOR, second), ctx.breaker.targets);
        ctx.breaker.state = BreakState.FINISHED;
        ctx.percepts.blocks.clear(second.x(), second.y(), second.z());
        task.tick(ctx);
        assertEquals("stepping in", task.phase());
        assertEquals(ANCHOR, lastOrder(), "flat, into the cell the stump stood in");

        arriveAt(ANCHOR);
        assertEquals("in the trunk — open 2 · dig in · 1 rise · 5 above", task.phase());
    }

    @Test
    void aBodyDugInPlansFromItsOwnLevel() {
        for (int y = BASE + 2; y < BASE + 7; y++) {
            ctx.percepts.blocks.set(0, y, 0, BlockKind.LOG); // the stump and the log over it dug
        }
        ctx.percepts.position = ANCHOR; // feet where the stump stood

        task.tick(ctx);
        assertEquals(0, ctx.mover.moveToCalls, "no walking out to walk back in");
        assertEquals(List.of("plan — open 0 · dig in · 1 rise · 5 above"), said("plan"));
        assertEquals(FellTree.Stage.PLANNED, task.stage());
    }

    // ── a crown in the way ───────────────────────────────────────────────────────────────────

    @Test
    void aLeafInTheWayOfAListedLeafIsChewedThroughFirst() {
        trunk();
        standSouth();
        onlyTheSouth();
        Pos listed = new Pos(0, BASE + 1, 1);
        Pos inTheWay = new Pos(0, BASE + 1, 2);
        ctx.percepts.blocks.set(listed.x(), listed.y(), listed.z(), BlockKind.LEAVES);
        ctx.percepts.blocks.set(inTheWay.x(), inTheWay.y(), inTheWay.z(), BlockKind.LEAVES);
        ctx.breaker.refuse.add(listed);
        ctx.breaker.obstructions.put(listed, inTheWay);

        task.tick(ctx);
        assertEquals(List.of(inTheWay), ctx.breaker.targets, "the leaf the breaker blames, first");
        ctx.breaker.state = BreakState.FINISHED;
        ctx.percepts.blocks.clear(inTheWay.x(), inTheWay.y(), inTheWay.z());
        ctx.breaker.refuse.clear();
        task.tick(ctx);
        assertEquals(List.of(inTheWay, listed), ctx.breaker.targets, "then the one it was after");
        assertEquals(List.of("cleared a leaf at " + at(inTheWay)), said("cleared"));
    }

    @Test
    void aBlockerThatIsNotALeafIsLeftToTheWalk() {
        trunk();
        standSouth();
        onlyTheSouth();
        Pos listed = new Pos(0, BASE + 1, 1);
        Pos stone = new Pos(0, BASE + 1, 2);
        ctx.percepts.blocks.set(listed.x(), listed.y(), listed.z(), BlockKind.LEAVES);
        ctx.percepts.blocks.set(stone.x(), stone.y(), stone.z(), BlockKind.OTHER);
        ctx.breaker.refuse.add(listed);
        ctx.breaker.obstructions.put(listed, stone);

        task.tick(ctx);
        assertTrue(ctx.breaker.targets.isEmpty(), "nothing the axe is for");
    }

    @Test
    void aWalkThatKeepsFailingWhileTheArmClearsIsNotStuck() {
        trunk();
        standSouth();
        onlyTheSouth();
        Pos leaf = new Pos(0, BASE, 1);
        ctx.percepts.blocks.set(leaf.x(), leaf.y(), leaf.z(), BlockKind.LEAVES);
        task.tick(ctx);
        ctx.mover.setState(MoveState.FAILED);
        ctx.mover.setFailure(MoveFailure.STRANDED);

        TaskStatus status = TaskStatus.RUNNING;
        int patience = FellTree.WALK_GIVE_UP * FellTree.WALK_RETRY_TICKS + 5;
        for (int i = 0; i < patience && status == TaskStatus.RUNNING; i++) {
            if (i % FellTree.WALK_RETRY_TICKS == 10) {
                ctx.breaker.state = BreakState.FINISHED; // the arm lands a leaf every couple of seconds
            }
            status = task.tick(ctx);
        }
        assertEquals(TaskStatus.RUNNING, status, "a failed walk is only stuck when the arm is idle too");
    }

    // ── what the arm cannot do ───────────────────────────────────────────────────────────────

    @Test
    void aBodyOnTopOfTheTreeWalksDownToASideFirst() {
        trunk();
        ctx.percepts.position = new Pos(0, BASE + 7, 0); // on the top log

        task.tick(ctx);
        assertEquals(1, ctx.mover.moveToCalls, "in the column is not on the step up");
        assertEquals(BASE, ctx.mover.lastY, "down to the ground beside the stump");
        assertTrue(ctx.breaker.targets.isEmpty(), "nothing is swung at from up there");
        assertTrue(said("plan").isEmpty());
    }

    @Test
    void aSwingRefusedAfterArrivalIsSaidOnceWithWhatIsInTheWay() {
        trunk();
        standSouth();
        task.tick(ctx);
        arriveAt(SOUTH);
        Pos second = new Pos(0, BASE + 1, 0);
        Pos stone = new Pos(0, BASE + 2, 1);
        ctx.breaker.refuseBegin = true; // every opening log, not just the first: the next is tried
        ctx.breaker.obstructions.put(second, stone);

        for (int i = 0; i < FellTree.SWING_REFUSED_TICKS + 2; i++) {
            task.tick(ctx);
        }
        assertEquals("opening the trunk", task.phase());
        assertEquals(List.of("cannot swing at " + at(second) + " from here — " + at(stone)
                + " is in the way"), said("cannot"), "one line, once it has lasted");
        TaskStatus status = TaskStatus.RUNNING;
        for (int i = 0; i < FellTree.GIVE_UP_TICKS && status == TaskStatus.RUNNING; i++) {
            status = task.tick(ctx);
        }
        assertEquals(TaskStatus.FAILED, status, "and a while later the tree is given up");
        assertTrue(task.failureDetail().contains("is in the way"), task.failureDetail());
        assertEquals(1, said("cannot").size(), "said once; the giving up has its own line");
    }

    @Test
    void aSideWhoseWalkKeepsFailingIsCrossedOffAndTheNextTaken() {
        trunk();
        standSouth();
        task.tick(ctx);
        assertEquals(SOUTH, task.chosen().orElseThrow());
        ctx.mover.setState(MoveState.FAILED);
        ctx.mover.setFailure(MoveFailure.STRANDED);

        TaskStatus status = TaskStatus.RUNNING;
        int budget = FellTree.WALK_GIVE_UP * FellTree.WALK_RETRY_TICKS + 5;
        for (int i = 0; i < budget && task.chosen().orElse(SOUTH).equals(SOUTH); i++) {
            status = task.tick(ctx);
        }
        assertEquals(TaskStatus.RUNNING, status, "the tree is not given up, the side is");
        assertEquals(new Pos(1, BASE, 0), task.chosen().orElseThrow(),
                "the next cheapest: east and west tie, east first in compass order");
        assertEquals(1, said("the S side is no good").size());
        assertTrue(said("the S side is no good").get(0).contains("stranded"));

        // And so on round the tree, until no side is left and the tree is given up.
        int patience = 4 * budget + FellTree.NO_WAY_IN_TICKS + 5;
        for (int i = 0; i < patience && status == TaskStatus.RUNNING; i++) {
            status = task.tick(ctx);
        }
        assertEquals(TaskStatus.FAILED, status);
        assertTrue(task.failureDetail().contains("4 side(s) given up on"), task.failureDetail());
        assertEquals(4, said("the ").stream().filter(l -> l.contains("side is no good")).count());
    }

    @Test
    void aRefusalWhileStillWalkingIsNotWorthALine() {
        trunk();
        standSouth();
        onlyTheSouth();
        Pos leaf = new Pos(0, BASE, 1);
        ctx.percepts.blocks.set(leaf.x(), leaf.y(), leaf.z(), BlockKind.LEAVES);
        ctx.breaker.refuse.add(leaf);
        task.tick(ctx);
        ctx.mover.setState(MoveState.MOVING);

        for (int i = 0; i < 2 * FellTree.SWING_REFUSED_TICKS; i++) {
            task.tick(ctx);
        }
        assertTrue(said("cannot").isEmpty(), "the walk is what cures it");
    }

    // ── the whole fell ───────────────────────────────────────────────────────────────────────

    @Test
    void fellsASevenLogBirchAndComesBackToTheGround() {
        trunk();
        pack(4);
        standSouth();

        assertEquals(TaskStatus.SUCCESS, drive(400));
        assertColumnGone(7);
        assertEquals(0, ctx.riser.ups, "the top was in reach from the base log");
        assertEquals(new Pos(0, BASE, 0), ctx.percepts.position, "on the dirt where the stump stood");
        assertEquals(List.of("felled the tree at " + at(ANCHOR) + " — 7 logs"), said("felled"));
        assertEquals(4, ctx.percepts.inventory.count("minecraft:birch_log"), "nothing spent");
    }

    @Test
    void aTallTrunkIsPillaredToTheMinimumClearedAndClimbedDownFrom() {
        trunk(12);
        pack(8);
        standSouth();

        assertEquals(TaskStatus.SUCCESS, drive(600));
        assertEquals(5, ctx.riser.ups, "up to where the top is in reach, and no higher");
        assertEquals("minecraft:birch_log", ctx.riser.lastItem, "on the tree's own wood");
        assertColumnGone(12);
        assertEquals(new Pos(0, BASE, 0), ctx.percepts.position, "the pillar came down with the trunk");
        assertEquals(List.of("felled the tree at " + at(ANCHOR) + " — 12 logs"), said("felled"),
                "the five placed and reclaimed are the body's own, not the tree's");
        assertEquals(3, ctx.percepts.inventory.count("minecraft:birch_log"),
                "five spent; what came back down is drops on the ground, which this world lacks");
    }

    @Test
    void onRaisedGroundTheStumpIsTakenFromOutsideAtTheEnd() {
        trunk(9);
        raisedGround();
        pack(4);
        standSouth();

        assertEquals(TaskStatus.SUCCESS, drive(600));
        assertEquals(1, ctx.riser.ups);
        assertColumnGone(9);
        assertEquals(new Pos(0, BASE + 1, 1), ctx.percepts.position, "stepped out to the south side");
        assertEquals(ANCHOR, ctx.breaker.targets.get(ctx.breaker.targets.size() - 1),
                "the stump, one below floor level, is the last swing");
    }

    @Test
    void onSunkenGroundTheDirtStaysAndTheBodyEndsOnIt() {
        trunk();
        sunkenGround();
        pack(4);
        standSouth();

        assertEquals(TaskStatus.SUCCESS, drive(600));
        assertEquals(1, ctx.riser.ups);
        assertColumnGone(7);
        assertEquals(BlockKind.OTHER, ctx.percepts.blocks.at(0, BASE - 1, 0),
                "the dirt under the stump was never the tree's");
        assertEquals(new Pos(0, BASE, 0), ctx.percepts.position, "on it, where the stump stood");
    }

    @Test
    void aLowSideDigsInRisesAndFellsTheLot() {
        trunk();
        standSouth();
        onlyTheSouth();
        pack(4);
        ctx.percepts.blocks.set(0, BASE + 2, 1, BlockKind.OTHER);

        assertEquals(TaskStatus.SUCCESS, drive(600));
        assertEquals(1, ctx.riser.ups);
        assertColumnGone(7);
        assertEquals(new Pos(0, BASE, 0), ctx.percepts.position);
    }

    @Test
    void groundTwoUpLeavesTheStumpBuriedAndSaysSo() {
        trunk(9);
        pack(4);
        standSouth();
        for (Pos cell : List.of(new Pos(0, BASE, -1), new Pos(1, BASE, 0), new Pos(0, BASE, 1),
                new Pos(-1, BASE, 0))) {
            ctx.percepts.blocks.set(cell.x(), cell.y(), cell.z(), BlockKind.OTHER);
            ctx.percepts.blocks.set(cell.x(), cell.y() + 1, cell.z(), BlockKind.OTHER);
        }

        assertEquals(TaskStatus.SUCCESS, drive(600));
        assertEquals(BlockKind.LOG, ctx.percepts.blocks.at(0, BASE, 0),
                "two below floor level is a pit: left buried");
        assertEquals(BlockKind.AIR, ctx.percepts.blocks.at(0, BASE + 1, 0), "one below: from outside");
        assertTrue(said("felled").get(0).endsWith("8 logs, 1 left buried below the ground"),
                said("felled").get(0));
    }

    @Test
    void noWayInForLongIsTheEndOfIt() {
        trunk();
        standSouth();
        for (Pos cell : List.of(new Pos(0, BASE - 1, -1), new Pos(1, BASE - 1, 0),
                new Pos(0, BASE - 1, 1), new Pos(-1, BASE - 1, 0))) {
            ctx.percepts.blocks.set(cell.x(), cell.y(), cell.z(), BlockKind.WATER);
        }

        TaskStatus status = TaskStatus.RUNNING;
        int ticks = 0;
        while (status == TaskStatus.RUNNING && ticks++ < 2 * FellTree.NO_WAY_IN_TICKS) {
            status = task.tick(ctx);
        }
        assertEquals(TaskStatus.FAILED, status);
        assertTrue(task.failureDetail().startsWith("no way in — "), task.failureDetail());
        assertTrue(ticks >= FellTree.NO_WAY_IN_TICKS, "not on the spot: the ground may change");
        assertEquals(1, said("gave up").size());
    }

    @Test
    void aRiseRefusedIsTheEndOfIt() {
        trunk(12);
        pack(8);
        standSouth();
        ctx.riser.refuse = true;

        assertEquals(TaskStatus.FAILED, drive(400));
        assertTrue(task.failureDetail().startsWith("the rise refused"), task.failureDetail());
        assertEquals(BreakState.IDLE, ctx.breaker.state, "the arm is let go of");
    }

    @Test
    void aBodyRisesOnWhateverLogItCarriesWhenItHasNoneOfThisTree() {
        trunk(12);
        ctx.percepts.inventory.add(new ItemStack("minecraft:spruce_log", 8, 64, ""));
        standSouth();

        assertEquals(TaskStatus.SUCCESS, drive(600));
        assertEquals("minecraft:spruce_log", ctx.riser.lastItem, "a log is a log to stand on");
        assertEquals(5, ctx.riser.ups);
    }

    @Test
    void nothingInThePackToRiseOnIsTheEndOfIt() {
        trunk(12);
        standSouth();

        assertEquals(TaskStatus.FAILED, drive(400));
        assertTrue(task.failureDetail().startsWith("nothing to rise on"), task.failureDetail());
    }

    @Test
    void aRestoredRiseCarriesOnFromWhereTheFeetAre() {
        trunk(12);
        pack(8);
        // As the world stands two rises in: the trunk opened, two logs placed, the feet on them.
        for (int y = BASE + 1; y <= BASE + 4; y++) {
            ctx.percepts.blocks.clear(0, y, 0);
        }
        ctx.percepts.blocks.set(0, BASE + 1, 0, BlockKind.LOG);
        ctx.percepts.blocks.set(0, BASE + 2, 0, BlockKind.LOG);
        ctx.percepts.position = new Pos(0, BASE + 3, 0);
        Climb climb = new Climb(new Pos(0, BASE + 1, 0), BASE + 6, true, false, List.of(),
                List.of(), List.of(ANCHOR), List.of(), true, List.of(), false, List.of());
        FellTree back = FellTree.restored(ANCHOR, FellTree.Stage.RISE,
                java.util.Optional.of(SOUTH), java.util.Optional.of(climb));

        assertEquals(TaskStatus.SUCCESS, drive(back, 600));
        assertEquals(3, ctx.riser.ups, "the three still to go");
        assertColumnGone(12);
        assertEquals(new Pos(0, BASE, 0), ctx.percepts.position);
    }

    // ── branches ─────────────────────────────────────────────────────────────────────────────

    @Test
    void aBranchInReachComesDownWithTheTrunk() {
        ctx.percepts.blocks.placeOak(0, 0); // four logs, a crown: a tree the split will own
        Pos branch = new Pos(1, BASE + 2, 0);
        ctx.percepts.blocks.set(branch.x(), branch.y(), branch.z(), BlockKind.LOG);
        ctx.percepts.blocks.setId(branch.x(), branch.y(), branch.z(), "minecraft:oak_log");
        standSouth();

        assertEquals(TaskStatus.SUCCESS, drive(600));
        assertEquals(List.of(branch), task.climb().orElseThrow().branches(),
                "the split's word: this tree's log, off its column");
        assertEquals(BlockKind.AIR, ctx.percepts.blocks.at(branch.x(), branch.y(), branch.z()));
        assertColumnGone(4);
        assertEquals(List.of("felled the tree at " + at(ANCHOR) + " — 5 logs"), said("felled"));
    }

    @Test
    void aTrunkGivenUpOnHalfwayIsReadWholeByTheNextVisit() {
        // The stump, three cells of air a fell from beside left, and two logs still up there.
        ctx.percepts.blocks.set(0, BASE, 0, BlockKind.LOG);
        ctx.percepts.blocks.set(0, BASE + 4, 0, BlockKind.LOG);
        ctx.percepts.blocks.set(0, BASE + 5, 0, BlockKind.LOG);
        standSouth();

        assertEquals(TaskStatus.SUCCESS, drive(400));
        assertEquals(List.of("plan — 3 to break from beside"), said("plan"), "not a one-log tree");
        assertColumnGone(6);
        assertEquals(List.of("felled the tree at " + at(ANCHOR) + " — 3 logs"), said("felled"));
    }

    @Test
    void aBranchOfThisTreeInTheWayOfItsTrunkIsChewedThroughFirst() {
        ctx.percepts.blocks.placeOak(0, 0);
        Pos branch = new Pos(1, BASE + 2, 0);
        ctx.percepts.blocks.set(branch.x(), branch.y(), branch.z(), BlockKind.LOG);
        ctx.percepts.blocks.setId(branch.x(), branch.y(), branch.z(), "minecraft:oak_log");
        Pos top = new Pos(0, BASE + 3, 0);
        ctx.breaker.refuse.add(top);
        ctx.breaker.obstructions.put(top, branch); // the branch hangs over the side, in the swing
        standSouth();

        for (int i = 0; i < 40 && !ctx.breaker.targets.contains(branch); i++) {
            drive(1);
        }
        assertTrue(ctx.breaker.targets.contains(branch), "its own branch, chewed through");
        assertFalse(ctx.breaker.targets.contains(top), "before the log it hid");
        ctx.breaker.refuse.clear();
        assertEquals(TaskStatus.SUCCESS, drive(600));
        assertColumnGone(4);
        assertEquals(List.of("felled the tree at " + at(ANCHOR) + " — 5 logs"), said("felled"));
    }

    @Test
    void aBranchOutOfReachFromAnyHeightIsLeftAndCounted() {
        ctx.percepts.blocks.placeOak(0, 0);
        // A limb off the cap, one cell out and up per log: the last is five out, farther than the
        // arm is long from the trunk at any height. The others set the height and come down.
        List<Pos> limb = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            Pos log = new Pos(i, BASE + 4 + i, 0);
            ctx.percepts.blocks.set(log.x(), log.y(), log.z(), BlockKind.LOG);
            ctx.percepts.blocks.setId(log.x(), log.y(), log.z(), "minecraft:oak_log");
            limb.add(log);
        }
        pack(8);
        standSouth();

        assertEquals(TaskStatus.SUCCESS, drive(800), "a branch never blocks the fell");
        assertEquals(5, task.climb().orElseThrow().branches().size());
        assertTrue(task.climb().orElseThrow().rises() > 0, "the limb, not the top log, set the height");
        Pos far = limb.get(4);
        for (Pos log : limb.subList(0, 4)) {
            assertEquals(BlockKind.AIR, ctx.percepts.blocks.at(log.x(), log.y(), log.z()), "at " + at(log));
        }
        assertEquals(BlockKind.LOG, ctx.percepts.blocks.at(far.x(), far.y(), far.z()));
        assertEquals(List.of("felled the tree at " + at(ANCHOR) + " — 8 logs, 1 branches out of reach"),
                said("felled"));
    }

    @Test
    void aRefusedBranchIsSkippedNotWaitedOn() {
        ctx.percepts.blocks.placeOak(0, 0);
        Pos branch = new Pos(1, BASE + 2, 0);
        ctx.percepts.blocks.set(branch.x(), branch.y(), branch.z(), BlockKind.LOG);
        ctx.breaker.refuse.add(branch);
        standSouth();

        assertEquals(TaskStatus.SUCCESS, drive(600));
        assertEquals(BlockKind.LOG, ctx.percepts.blocks.at(branch.x(), branch.y(), branch.z()));
        assertTrue(said("felled").get(0).endsWith("1 branches out of reach"), said("felled").get(0));
        assertTrue(said("cannot").isEmpty(), "a branch refused is nobody's problem");
    }

    // ── a giant ──────────────────────────────────────────────────────────────────────────────

    /** A 2×2 trunk on the anchor's square, {@code logs} tall, of spruce. */
    private void giant(int logs) {
        for (int y = BASE; y < BASE + logs; y++) {
            for (int[] d : new int[][] {{0, 0}, {1, 0}, {1, 1}, {0, 1}}) {
                ctx.percepts.blocks.set(d[0], y, d[1], BlockKind.LOG);
                ctx.percepts.blocks.setId(d[0], y, d[1], "minecraft:spruce_log");
            }
        }
    }

    private void assertGiantGone(int logs) {
        for (int y = BASE; y < BASE + logs; y++) {
            for (int[] d : new int[][] {{0, 0}, {1, 0}, {1, 1}, {0, 1}}) {
                assertEquals(BlockKind.AIR, ctx.percepts.blocks.at(d[0], y, d[1]),
                        "at (" + d[0] + ", " + y + ", " + d[1] + ")");
            }
        }
    }

    @Test
    void aGiantIsSpiralledUpAndUnwoundWithNothingPlaced() {
        giant(12);
        standSouth();

        task.tick(ctx);
        assertEquals(new Pos(0, BASE, 2), task.chosen().orElseThrow(), "the nearer of the two south cells");
        assertEquals(TaskStatus.SUCCESS, drive(1500));
        Climb climb = task.climb().orElseThrow();
        assertTrue(climb.giant());
        assertEquals(new Pos(0, BASE + 1, 1), climb.stand(), "the entry column is the one beside the side");
        assertEquals(3, climb.stepIn().size(), "the body's two cells and one to hop on from");
        assertEquals(5, climb.rises(), "five slots up, the top priced from the diagonal column");
        assertEquals(0, ctx.riser.ups, "nothing placed: the stairs are the tree's own logs");
        assertGiantGone(12);
        assertEquals(new Pos(0, BASE, 1), ctx.percepts.position, "back on the ground in the entry column");
        assertEquals(List.of("felled the tree at " + at(ANCHOR) + " — 48 logs"), said("felled"));
    }

    @Test
    void aHopIsNotReadUntilTheFeetAreOnSomething() {
        giant(12);
        standSouth();
        // Up to the first hop of the spiral: the slot open, the walk into it ordered.
        TaskStatus status = TaskStatus.RUNNING;
        for (int i = 0; i < 200 && ctx.mover.moveToCalls < 3; i++) {
            status = drive(1);
        }
        assertEquals(TaskStatus.RUNNING, status);
        assertEquals(FellTree.Stage.SPIRAL, task.stage());
        Pos slot = new Pos(ctx.mover.lastX, ctx.mover.lastY, ctx.mover.lastZ);
        int breaks = ctx.breaker.targets.size();

        // Mid-hop: the feet read a cell higher than the floor, and there is nothing under them.
        ctx.percepts.position = new Pos(slot.x(), slot.y() + 1, slot.z());
        ctx.mover.setState(MoveState.MOVING);
        for (int i = 0; i < 10; i++) {
            task.tick(ctx);
        }
        assertEquals(breaks, ctx.breaker.targets.size(), "nothing is begun off a mid-air read");
        assertEquals(3, ctx.mover.moveToCalls, "and no hop is ordered off one either");

        // Landed, on the stair: the spiral goes on from the real cell.
        ctx.percepts.position = slot;
        ctx.mover.setState(MoveState.ARRIVED);
        task.tick(ctx);
        task.tick(ctx);
        assertTrue(ctx.breaker.targets.size() > breaks, "the next slot is opened from where it stands");
    }

    @Test
    void aShortGiantIsAllFromBesideItsBasesLast() {
        giant(5);
        standSouth();

        assertEquals(TaskStatus.SUCCESS, drive(600));
        Climb climb = task.climb().orElseThrow();
        assertTrue(climb.giant());
        assertEquals(0, climb.rises());
        assertGiantGone(5);
        assertEquals(List.of("felled the tree at " + at(ANCHOR) + " — 20 logs"), said("felled"));
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
