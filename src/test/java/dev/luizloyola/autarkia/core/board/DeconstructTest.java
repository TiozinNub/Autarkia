package dev.luizloyola.autarkia.core.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.board.WorkItem;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.BreakBlock;
import dev.luizloyola.anima.core.brain.task.FakeContext;
import dev.luizloyola.anima.core.brain.task.PutAwaySurplus;
import dev.luizloyola.anima.core.brain.task.TakeItems;
import dev.luizloyola.anima.core.brain.task.Task;
import dev.luizloyola.anima.core.inv.ItemStack;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Taking blocks down: a chest is emptied before it is broken, a block is settled when the world no
 * longer holds it, and one that will not come down is given up rather than worked for ever.
 */
class DeconstructTest {

    private static final Pos CHEST = new Pos(4, 64, 4);
    private static final Pos BENCH = new Pos(6, 64, 4);

    private static Deconstruct starterBase() {
        return new Deconstruct(List.of(new Deconstruct.Target(CHEST, "minecraft:chest", true),
                new Deconstruct.Target(BENCH, "minecraft:crafting_table", false)), "moved in", 0.5);
    }

    private static FakeContext standing() {
        FakeContext ctx = new FakeContext();
        ctx.percepts.blocks.setId(CHEST.x(), CHEST.y(), CHEST.z(), "minecraft:chest");
        ctx.percepts.blocks.setId(BENCH.x(), BENCH.y(), BENCH.z(), "minecraft:crafting_table");
        return ctx;
    }

    private static List<Task> steps(Task root, FakeContext ctx) {
        DeconstructErrand errand = assertInstanceOf(DeconstructErrand.class, root);
        return errand.methods().get(0).decompose(ctx);
    }

    @Test
    void eachBlockIsItsOwnItemSoSeveralCanWorkAtOnce() {
        Deconstruct project = starterBase();

        assertEquals(2, project.open().size());
        assertFalse(project.finished());
    }

    @Test
    void aChestNotKnownEmptyIsEmptiedAndTheLoadCarriedHome() {
        FakeContext ctx = standing();
        WorkItem chest = starterBase().open().get(0);

        List<Task> steps = steps(chest.root(), ctx);

        assertTrue(steps.stream().anyMatch(step -> step instanceof TakeItems), steps.toString());
        assertTrue(steps.stream().anyMatch(step -> step instanceof PutAwaySurplus), steps.toString());
        assertFalse(steps.stream().anyMatch(step -> step instanceof BreakBlock),
                "nothing is broken while it may still hold the party's goods");
    }

    @Test
    void aChestFoundEmptyIsBroken() {
        FakeContext ctx = standing();
        ctx.knowledge.sawInside(CHEST, List.<ItemStack>of(), 10L, 64);
        WorkItem chest = starterBase().open().get(0);

        List<Task> steps = steps(chest.root(), ctx);

        assertTrue(steps.stream().anyMatch(step -> step instanceof BreakBlock), steps.toString());
        assertFalse(steps.stream().anyMatch(step -> step instanceof TakeItems), steps.toString());
    }

    @Test
    void aWorkbenchIsSimplyBroken() {
        List<Task> steps = steps(starterBase().open().get(1).root(), standing());

        assertTrue(steps.stream().anyMatch(step -> step instanceof BreakBlock));
        assertFalse(steps.stream().anyMatch(step -> step instanceof TakeItems));
    }

    @Test
    void aBlockIsSettledWhenTheWorldNoLongerHoldsIt() {
        Deconstruct project = starterBase();
        FakeContext ctx = standing();
        WorkItem chest = project.open().get(0);

        project.completed(chest, ctx);
        assertEquals(2, project.open().size(), "a trip that left the chest standing was an emptying trip");

        ctx.percepts.blocks.setId(CHEST.x(), CHEST.y(), CHEST.z(), "minecraft:air");
        project.completed(chest, ctx);
        assertEquals(1, project.open().size());
        assertEquals(List.of(CHEST), project.settled());

        ctx.percepts.blocks.setId(BENCH.x(), BENCH.y(), BENCH.z(), "minecraft:air");
        project.completed(project.open().get(0), ctx);
        assertTrue(project.finished());
    }

    @Test
    void aBlockThatWillNotComeDownIsGivenUp() {
        Deconstruct project = starterBase();
        FakeContext ctx = standing();
        WorkItem bench = project.open().get(1);

        for (int trip = 0; trip < Deconstruct.MAX_TRIPS; trip++) {
            project.failed(bench, ctx);
        }

        assertEquals(1, project.open().size(), "given up after " + Deconstruct.MAX_TRIPS + " trips");
        assertEquals(List.of(BENCH), project.settled());
    }

    @Test
    void itComesBackFromItsSaveWhereItWas() {
        Deconstruct project = starterBase();
        FakeContext ctx = standing();
        ctx.percepts.blocks.setId(CHEST.x(), CHEST.y(), CHEST.z(), "minecraft:air");
        project.completed(project.open().get(0), ctx);
        project.failed(project.open().get(0), ctx);

        Deconstruct back = Deconstruct.restore(project.snapshot());

        assertEquals(project.snapshot(), back.snapshot());
        assertEquals(1, back.open().size());
    }
}
