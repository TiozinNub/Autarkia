package dev.luizloyola.autarkia.core.patch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.gate.Act;
import dev.luizloyola.anima.core.brain.gate.Gate;
import dev.luizloyola.anima.core.brain.knowledge.AgentKnowledge;
import dev.luizloyola.anima.core.brain.knowledge.PoiMemory;
import dev.luizloyola.anima.core.brain.knowledge.Region;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.FakeContext;
import dev.luizloyola.anima.core.brain.task.GoTo;
import dev.luizloyola.anima.core.brain.task.ObtainItem;
import dev.luizloyola.anima.core.brain.task.Task;
import dev.luizloyola.anima.core.brain.task.TaskStatus;
import dev.luizloyola.anima.core.inv.ItemStack;
import dev.luizloyola.autarkia.core.board.Stock;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Stone from where it shows: a remembered patch, a pickaxe first, and a barren patch rested. */
class MineStoneTest {

    private final FakeContext ctx = new FakeContext();
    private final Pos patch = new Pos(12, 64, 0);

    @BeforeEach
    void setUp() {
        ctx.percepts.position = new Pos(0, 64, 0);
        Stock.furnaceStoneBy(id -> id.equals("minecraft:cobblestone"));
    }

    @AfterEach
    void tearDown() {
        Gate.install(Gate.OPEN);
        Stock.furnaceStoneBy(id -> false);
    }

    private void remember() {
        ctx.knowledge.note(new PoiMemory(Landmarks.STONE_POI, patch,
                new Region(new Pos(10, 64, -2), new Pos(14, 64, 2)), 12, false, 0L),
                AgentKnowledge.maxPerKind(ctx.profile()));
    }

    private static MineStone forStone() {
        return new MineStone(Stock.FURNACE_STONE);
    }

    @Test
    void withNoStoneKnownThereIsNoWay() {
        assertFalse(forStone().applicable(ctx));
    }

    @Test
    void aRememberedPatchIsAWayPricedByTheWalkAndTheWork() {
        remember();
        assertTrue(forStone().applicable(ctx));
        assertEquals(12 + MineStone.WORK, forStone().estimateCost(ctx), 1e-9);
    }

    @Test
    void aBodyWithNoPickaxeGetsOneFirst() {
        remember();
        List<Task> plan = forStone().decompose(ctx);
        assertEquals(Stock.PICKAXES, assertInstanceOf(ObtainItem.class, plan.get(0)).spec(),
                "stone mined bare-handed drops nothing");
        assertInstanceOf(GoTo.class, plan.get(1));
        assertInstanceOf(MinePatch.class, plan.get(2));
    }

    @Test
    void aBodyWithOneGoesStraightThere() {
        remember();
        ctx.percepts.inventory.set(0, ItemStack.of("minecraft:wooden_pickaxe", 1, 1));
        assertInstanceOf(GoTo.class, forStone().decompose(ctx).get(0));
    }

    @Test
    void aTripRestsThePatch() {
        remember();
        forStone().decompose(ctx);
        assertFalse(forStone().applicable(ctx), "what showed has been mined; come back later");
    }

    @Test
    void aBodyTheGateKeepsFromDiggingDoesNot() {
        remember();
        Gate.install(new Gate.Policy() {
            @Override
            public Optional<String> refuseItem(AgentId body, String itemId) {
                return Optional.empty();
            }

            @Override
            public Optional<String> refuseAct(AgentId body, Act act) {
                return act.equals(MineStone.ACT) ? Optional.of("not in the Wood Age") : Optional.empty();
            }
        });
        ctx.gate = Gate.viewFor(ctx.self, ctx.journal());
        assertFalse(forStone().applicable(ctx));
    }

    @Test
    void onlyTheStoneShowingIsMinedNearestFirst() {
        ctx.percepts.blocks.set(13, 64, 0, Landmarks.STONE);
        ctx.percepts.blocks.set(11, 64, 1, Landmarks.STONE);
        ctx.percepts.blocks.set(12, 64, 0, Landmarks.STONE);
        ctx.percepts.blocks.set(12, 65, 0, Landmarks.STONE);
        ctx.percepts.position = new Pos(10, 64, 0);

        List<Pos> cells = new MinePatch(patch, new Region(new Pos(10, 64, -2), new Pos(14, 65, 2)),
                Stock.FURNACE_STONE).exposed(ctx);

        assertEquals(new Pos(11, 64, 1), cells.get(0));
        assertTrue(cells.contains(new Pos(12, 65, 0)), "the top of the column");
        assertFalse(cells.contains(new Pos(12, 64, 0)), "under another stone: not showing");
    }

    @Test
    void aFirstBlockThatGaveNothingRestsThePatchForADay() {
        remember();
        MinePatch.Yield yield = new MinePatch.Yield(patch, Stock.FURNACE_STONE, 0);

        assertEquals(TaskStatus.FAILED, yield.tick(ctx), "granite gives granite");
        ctx.percepts.time = MinePatch.BARREN_TICKS - 1;
        assertFalse(forStone().applicable(ctx));
    }

    @Test
    void aFirstBlockThatGaveCobblestoneGoesOn() {
        ctx.percepts.inventory.set(0, ItemStack.of("minecraft:cobblestone", 1, 64));
        assertEquals(TaskStatus.SUCCESS, new MinePatch.Yield(patch, Stock.FURNACE_STONE, 0).tick(ctx));
    }
}
