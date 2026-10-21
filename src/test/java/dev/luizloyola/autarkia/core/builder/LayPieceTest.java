package dev.luizloyola.autarkia.core.builder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.act.BlockPlacer.Refusal;
import dev.luizloyola.anima.core.brain.act.MoveState;
import dev.luizloyola.anima.core.brain.act.Placing;
import dev.luizloyola.anima.core.brain.knowledge.BlockKind;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.FakeContext;
import dev.luizloyola.anima.core.brain.task.FakePlacer;
import dev.luizloyola.anima.core.brain.task.TaskExecutor;
import dev.luizloyola.anima.core.brain.task.TaskStatus;
import dev.luizloyola.anima.core.inv.ItemStack;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class LayPieceTest {

    private static final String PLANKS = "minecraft:oak_planks";

    /** Steps far enough apart that each is walked to from its own stand. */
    private static Laying step(int x) {
        return new Laying(Section.WALLS, new Placing(PLANKS, new Pos(x, 64, 0), PLANKS, Map.of()), List.of(),
                new Pos(x, 64, 2), 1);
    }

    /**
     * Runs a piece, playing the body: a walk arrives, and a block placed stands in the world and
     * leaves the pack. Returns where the body walked to.
     */
    private static List<Pos> fly(LayPiece piece, FakeContext ctx, TaskExecutor executor) {
        executor.run(piece, ctx);
        List<Pos> walkedTo = new ArrayList<>();
        int walks = 0;
        int placed = 0;
        for (int tick = 0; tick < 4000 && executor.isBusy(); tick++) {
            ctx.percepts.time++;
            if (ctx.mover.moveToCalls > walks) {
                walks = ctx.mover.moveToCalls;
                ctx.percepts.position = new Pos(ctx.mover.lastX, ctx.mover.lastY, ctx.mover.lastZ);
                walkedTo.add(ctx.percepts.position);
                ctx.mover.setState(MoveState.ARRIVED);
            }
            for (; placed < ctx.placer.placings.size(); placed++) {
                Placing put = ctx.placer.placings.get(placed);
                ctx.percepts.blocks.set(put.cell().x(), put.cell().y(), put.cell().z(), BlockKind.OTHER);
                ctx.percepts.blocks.setId(put.cell().x(), put.cell().y(), put.cell().z(), put.block());
                ctx.percepts.inventory.remove(put.itemId(), 1);
            }
            executor.tick(ctx);
        }
        return walkedTo;
    }

    @Test
    void aStepOutOfItsItemEndsThePiece() {
        FakeContext ctx = new FakeContext();
        ctx.percepts.position = new Pos(0, 64, 30);
        ctx.percepts.inventory.add(ItemStack.of(PLANKS, 1, 64));
        TaskExecutor executor = new TaskExecutor();
        List<Pos> walkedTo = fly(new LayPiece(List.of(step(0), step(10), step(20), step(30))), ctx, executor);

        assertEquals(Optional.of(TaskStatus.FAILED), executor.lastStatus(), "a piece out of planks stops short");
        assertEquals(1, ctx.placer.placed.size());
        assertEquals(List.of(new Pos(0, 64, 2)), walkedTo, "no walk to a step there is nothing to place for");
    }

    @Test
    void aStepRefusedForWantOfTheNextGoesInOnTheWayBack() {
        FakeContext ctx = new FakeContext();
        ctx.percepts.position = new Pos(0, 64, 30);
        ctx.percepts.inventory.add(ItemStack.of(PLANKS, 3, 64));
        Pos last = new Pos(20, 64, 0);
        ctx.placer.rule = p -> p.cell().equals(new Pos(10, 64, 0)) && ctx.percepts.blocks.empty(20, 64, 0)
                ? Optional.of(Refusal.NOTHING_AGAINST) : Optional.empty();
        TaskExecutor executor = new TaskExecutor();
        fly(new LayPiece(List.of(step(0), step(10), step(20))), ctx, executor);

        assertEquals(Optional.of(TaskStatus.SUCCESS), executor.lastStatus());
        assertEquals(List.of(new Pos(0, 64, 0), last, new Pos(10, 64, 0)),
                ctx.placer.placed.stream().map(FakePlacer.Placement::cell).toList());
    }

    /** Where the drawing's cells are put in the test world: clear of the fake ground at y 63. */
    private static final int LIFT = 70;

    private static Pos at(Cell cell) {
        return new Pos(cell.x(), cell.layer() + LIFT, cell.z());
    }

    private static Laying laying(BuildOrder.Placed placed) {
        String block = placed.step().state().block();
        return new Laying(placed.step().section(), new Placing(block, at(placed.step().cell()), block, Map.of()),
                List.of(), at(placed.stand()), 1).in(placed.wave(), placed.piece());
    }

    /**
     * The shipped house's roof piece that a flight left three stairs short (2026-10-20): its
     * eleventh stair would not go in, and the two after it are placed against it alone until the
     * row is up from the other end. A block goes in only against one already standing.
     */
    @Test
    void theShippedRoofPieceGoesUpWholeWhenItsEleventhStairWillNotGoIn() throws IOException {
        var bp = BuildOrderTest.bind(BuildOrderTest.read("/data/autarkia/autarkia/blueprint/basic_wooden_house.bp"));
        var plan = BuildOrderTest.plan(bp, Map.of("base", "lv1", "beds", "none", "attic", "none"));
        List<BuildOrder.Placed> order = BuildOrder.prove(plan, BuildOrderTest.DICT).order();
        int stuck = -1;
        for (int i = 0; i < order.size(); i++) {
            if (order.get(i).step().cell().equals(new Cell(8, 3, 4))) {
                stuck = i;
            }
        }
        int piece = order.get(stuck).piece();
        List<BuildOrder.Placed> steps = order.stream().filter(p -> p.piece() == piece).toList();
        int first = order.indexOf(steps.get(0));
        assertEquals(10, stuck - first, "the stair is the piece's eleventh, as in the flight");

        FakeContext ctx = new FakeContext();
        for (BuildOrder.Placed before : order.subList(0, first)) {
            for (Cell cell : before.step().cells()) {
                Pos pos = at(cell);
                ctx.percepts.blocks.set(pos.x(), pos.y(), pos.z(), BlockKind.OTHER);
                ctx.percepts.blocks.setId(pos.x(), pos.y(), pos.z(), before.step().state().block());
            }
        }
        steps.forEach(p -> ctx.percepts.inventory.add(ItemStack.of(p.step().state().block(), 1, 64)));
        ctx.percepts.position = at(steps.get(0).stand());
        Pos eleventh = at(order.get(stuck).step().cell());
        Set<Pos> tried = new java.util.HashSet<>();
        ctx.placer.rule = p -> {
            Pos c = p.cell();
            boolean against = !ctx.percepts.blocks.empty(c.x() + 1, c.y(), c.z())
                    || !ctx.percepts.blocks.empty(c.x() - 1, c.y(), c.z())
                    || !ctx.percepts.blocks.empty(c.x(), c.y() + 1, c.z())
                    || !ctx.percepts.blocks.empty(c.x(), c.y() - 1, c.z())
                    || !ctx.percepts.blocks.empty(c.x(), c.y(), c.z() + 1)
                    || !ctx.percepts.blocks.empty(c.x(), c.y(), c.z() - 1);
            if (!against) {
                return Optional.of(Refusal.NOTHING_AGAINST);
            }
            // The stand the flight had for it did not reach; a later one does.
            return c.equals(eleventh) && tried.add(c) ? Optional.of(Refusal.OUT_OF_REACH) : Optional.empty();
        };
        TaskExecutor executor = new TaskExecutor();
        fly(new LayPiece(steps.stream().map(LayPieceTest::laying).toList()), ctx, executor);

        Set<Pos> wanted = new TreeSet<>(java.util.Comparator.comparing(Pos::toString));
        steps.forEach(p -> wanted.add(at(p.step().cell())));
        Set<Pos> placed = new TreeSet<>(java.util.Comparator.comparing(Pos::toString));
        ctx.placer.placed.forEach(p -> placed.add(p.cell()));
        assertEquals(Optional.of(TaskStatus.SUCCESS), executor.lastStatus());
        assertEquals(wanted, placed, "every stair of the row stands after one trip");
        assertTrue(ctx.placer.attempts.size() > steps.size(), "some went in only on the way back");
    }

    @Test
    void aTripThatPlacedNothingFailsAndSaysSo() {
        FakeContext ctx = new FakeContext();
        ctx.percepts.position = new Pos(0, 64, 30);
        ctx.percepts.inventory.add(ItemStack.of(PLANKS, 2, 64));
        ctx.placer.refuse = true;
        TaskExecutor executor = new TaskExecutor();
        fly(new LayPiece(List.of(step(0), step(10))), ctx, executor);

        assertEquals(Optional.of(TaskStatus.FAILED), executor.lastStatus(), "passing over every step is no success");
        assertEquals(Optional.of("none of the 2 would go in"), executor.failureReason());
    }
}
