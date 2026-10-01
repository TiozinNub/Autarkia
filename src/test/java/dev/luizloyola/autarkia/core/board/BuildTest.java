package dev.luizloyola.autarkia.core.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.act.Placing;
import dev.luizloyola.anima.core.brain.board.WorkItem;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.FakeContext;
import dev.luizloyola.anima.core.inv.ItemCall;
import dev.luizloyola.anima.core.inv.ItemStack;
import dev.luizloyola.autarkia.core.builder.LayPiece;
import dev.luizloyola.autarkia.core.builder.Laying;
import dev.luizloyola.autarkia.core.builder.Section;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BuildTest {

    private static final String PLANKS = "minecraft:oak_planks";
    private static final String SLAB = "minecraft:oak_slab";

    /** {@code floor} planks along x at y 64, then {@code walls} more a layer up. */
    private static List<Laying> house(int floor, int walls) {
        List<Laying> order = new ArrayList<>();
        for (int x = 0; x < floor; x++) {
            order.add(step(Section.FLOOR, PLANKS, x, 64, 1));
        }
        for (int x = 0; x < walls; x++) {
            order.add(step(Section.WALLS, PLANKS, x, 65, 1));
        }
        return order;
    }

    private static Laying step(Section section, String item, int x, int y, int count) {
        return new Laying(section, new Placing(item, new Pos(x, y, 0), item, Map.of()), List.of(),
                new Pos(x, 64, 2), count);
    }

    private static Build build(List<Laying> order) {
        Build build = new Build(UUID.randomUUID(), "autarkia:test_house", order, 0.5);
        build.tick(0);
        return build;
    }

    private static WorkItem only(Build build) {
        assertEquals(1, build.open().size(), build.describe());
        return build.open().get(0);
    }

    private static void stand(FakeContext ctx, Laying step) {
        ctx.percepts.blocks.setId(step.cell().x(), step.cell().y(), step.cell().z(), step.block());
    }

    @Test
    void onePieceIsOfferedARunOfOneSectionFromTheFirstStep() {
        Build build = build(house(3, 30));
        WorkItem piece = only(build);
        assertEquals("build 3 of autarkia:test_house's floor from (0, 64, 0)", piece.describe());
        LayPiece root = assertInstanceOf(LayPiece.class, piece.root());
        assertEquals(3, root.steps().size());
    }

    @Test
    void aPieceIsAtMostOneTripLong() {
        Build build = build(house(0, 30));
        LayPiece root = assertInstanceOf(LayPiece.class, only(build).root());
        assertEquals(Build.PIECE, root.steps().size());
    }

    @Test
    void theKitIsTheBlocksThePiecePlacesCountedAsPlaced() {
        List<Laying> order = new ArrayList<>(house(2, 0));
        order.add(step(Section.FLOOR, SLAB, 5, 64, 2));
        List<ItemCall> calls = only(build(order)).kit().calls();
        assertEquals(2, calls.size());
        assertEquals(2, calls.get(0).count());
        assertEquals(2, calls.get(1).count(), "a double slab is two");
        assertTrue(calls.stream().allMatch(c -> c.strength() == ItemCall.Strength.NEED));
    }

    @Test
    void whatStandsIsReadOffTheWorldAndTheNextPieceFollows() {
        List<Laying> order = house(3, 2);
        Build build = build(order);
        FakeContext ctx = new FakeContext();
        stand(ctx, order.get(0));
        stand(ctx, order.get(1));
        build.completed(only(build), ctx);
        assertEquals("build 1 of autarkia:test_house's floor from (2, 64, 0)", only(build).describe(),
                "a step the trip did not place is offered again, before the walls");

        stand(ctx, order.get(2));
        build.completed(only(build), ctx);
        assertEquals("build 2 of autarkia:test_house's walls from (0, 65, 0)", only(build).describe());

        stand(ctx, order.get(3));
        stand(ctx, order.get(4));
        build.completed(only(build), ctx);
        assertTrue(build.finished());
        assertEquals(5, build.placed());
    }

    @Test
    void aBuilderShortOfBlocksMakesThePieceWait() {
        Build build = build(house(3, 0));
        FakeContext ctx = new FakeContext();
        ctx.percepts.time = 100;
        build.failed(only(build), ctx);
        build.tick(100);
        assertTrue(build.open().isEmpty());
        assertTrue(build.describe().contains("short of 3 " + PLANKS), build.describe());

        build.tick(100 + Build.MATERIAL_WAIT);
        assertEquals(1, build.open().size());
    }

    @Test
    void aStepThatWillNotGoInIsHandedBackAndTheRestGoOn() {
        List<Laying> order = house(2, 0);
        Build build = build(order);
        FakeContext ctx = new FakeContext();
        ctx.percepts.inventory.set(0, ItemStack.of(PLANKS, 2, 64));
        long now = 0;
        for (int tries = 0; tries < Build.REFUSE_AFTER; tries++) {
            now += 1_000_000;
            build.tick(now);
            ctx.percepts.time = now;
            build.failed(only(build), ctx);
        }
        build.tick(now + 1_000_000);
        assertEquals(1, build.refused().size());
        assertEquals("build 1 of autarkia:test_house's floor from (1, 64, 0)", only(build).describe());
        stand(ctx, order.get(1));
        build.completed(only(build), ctx);
        assertTrue(build.finished());
        assertTrue(build.describe().contains("1 handed back"), build.describe());
    }

    @Test
    void aBuildPostedAgainSkipsWhatStands() {
        List<Laying> order = house(3, 0);
        Build build = new Build(UUID.randomUUID(), "autarkia:test_house", order, 0.5);
        build.standing(step -> step.cell().x() < 2);
        build.tick(0);
        assertEquals("build 1 of autarkia:test_house's floor from (2, 64, 0)", only(build).describe());
    }

    @Test
    void aRestoredBuildCarriesOnWhereItWas() {
        List<Laying> order = house(3, 2);
        Build build = build(order);
        FakeContext ctx = new FakeContext();
        for (int i = 0; i < 3; i++) {
            stand(ctx, order.get(i));
        }
        build.completed(only(build), ctx);
        Build.State saved = build.snapshot();
        Build back = Build.restore(saved, 0);
        assertEquals(build.describe(), back.describe());
        assertEquals(only(build).describe(), only(back).describe());
        assertEquals(saved, back.snapshot());
        assertFalse(back.finished());
    }
}
