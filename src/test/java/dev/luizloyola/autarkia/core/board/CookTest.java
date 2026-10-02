package dev.luizloyola.autarkia.core.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.board.WorkItem;
import dev.luizloyola.anima.core.brain.gate.Act;
import dev.luizloyola.anima.core.brain.gate.Gate;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.CookAtCampfire;
import dev.luizloyola.anima.core.brain.task.FakeContext;
import dev.luizloyola.anima.core.brain.task.RawFood;
import dev.luizloyola.anima.core.brain.task.ReadyFood;
import dev.luizloyola.anima.core.brain.task.Task;
import dev.luizloyola.anima.core.craft.Campfire;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** HOME's raw food cooked at its campfire by one member and carried home, once. */
class CookTest {

    private final FakeContext ctx = new FakeContext();
    private final Pos fire = new Pos(3, 64, 2);

    @org.junit.jupiter.api.BeforeEach
    void foods() {
        ctx.percepts.food("minecraft:beef", new dev.luizloyola.anima.core.agent.FoodValue(3, 1.8F, false));
        ctx.percepts.cooked("minecraft:beef", new dev.luizloyola.anima.core.agent.FoodValue(8, 12.8F, false));
        ReadyFood.install(ctx.percepts.foods());
    }

    @AfterEach
    void tearDown() {
        Gate.install(Gate.OPEN);
        ReadyFood.install(null);
    }

    @Test
    void theErrandCooksAtTheFireThenCarriesItHome() {
        Cook cook = new Cook(fire, 12, 0.4);
        Task root = cook.open().get(0).root();

        List<Task> steps = assertInstanceOf(CookErrand.class, root).methods().get(0).decompose(ctx);

        CookAtCampfire there = assertInstanceOf(CookAtCampfire.class, steps.get(0));
        assertEquals(fire, there.at());
        assertEquals(RawFood.SPEC, there.raw());
        assertEquals(12, there.count());
        BringBack home = assertInstanceOf(BringBack.class, steps.get(1));
        assertEquals(ReadyFood.SPEC, home.spec());
    }

    @Test
    void itIsOneItemDoneOnce() {
        Cook cook = new Cook(fire, 12, 0.4);
        WorkItem item = cook.open().get(0);

        cook.completed(item, ctx);

        assertTrue(cook.finished());
        assertTrue(cook.open().isEmpty());
    }

    @Test
    void cookingIsAnActTheGateDecides() {
        Cook cook = new Cook(fire, 12, 0.4);
        WorkItem item = cook.open().get(0);
        assertTrue(cook.offerableTo(item, ctx.self, ctx));

        Gate.install(new Gate.Policy() {
            @Override
            public Optional<String> refuseItem(AgentId body, String itemId) {
                return Optional.empty();
            }

            @Override
            public Optional<String> refuseAct(AgentId body, Act act) {
                return act.equals(Cook.ACT) ? Optional.of("not reached") : Optional.empty();
            }
        });
        ctx.gate = Gate.viewFor(ctx.self, ctx.journal());

        assertFalse(cook.offerableTo(item, ctx.self, ctx));
    }

    @Test
    void aCampfireFoundGoneEndsIt() {
        ctx.claim(Campfire.POI, new Pos(40, 64, 0));
        Cook cook = new Cook(fire, 12, 0.4);

        cook.failed(cook.open().get(0), ctx.self, ctx);

        assertTrue(cook.finished(), "the party has no campfire at (3, 64, 2)");
    }

    @Test
    void nothingRawLeftEndsIt() {
        ctx.claim(Campfire.POI, fire);
        Cook cook = new Cook(fire, 12, 0.4);

        cook.failed(cook.open().get(0), ctx.self, ctx);

        assertTrue(cook.finished(), "a job for twelve was offered for ever once the beef had gone (2026-10-01)");
    }

    /** Forest, 2026-10-02: a chest boxed in by the base's workbench, furnace, a second chest and the hill. */
    private void walledInStoreHolding(Pos at, String id, int count) {
        ctx.claim(dev.luizloyola.anima.core.store.Store.POI, at);
        ctx.knowledge.sawInside(at, List.of(dev.luizloyola.anima.core.inv.ItemStack.of(id, count, 64)), 0L,
                dev.luizloyola.anima.core.brain.knowledge.AgentKnowledge.maxPerKind(ctx.profile()));
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                ctx.percepts.blocks.set(at.x() + dx, at.y(), at.z() + dz,
                        dev.luizloyola.anima.core.brain.knowledge.BlockKind.OTHER);
            }
        }
        ctx.percepts.blocks.set(at.x(), at.y(), at.z(), dev.luizloyola.anima.core.store.Store.BLOCK);
    }

    @Test
    void rawFoodOnlyInAStoreNoWalkReachesEndsIt() {
        ctx.claim(Campfire.POI, fire);
        walledInStoreHolding(new Pos(-8, 64, 0), "minecraft:beef", 1);
        Cook cook = new Cook(fire, 1, 0.4);

        cook.failed(cook.open().get(0), ctx.self, ctx);

        assertTrue(cook.finished(), "Hannah's cook was claimed and failed on a walled-in mutton 336 times");
    }

    @Test
    void failuresInARowDoubleTheWait() {
        ctx.claim(Campfire.POI, fire);
        ctx.percepts.inventory.set(0, dev.luizloyola.anima.core.inv.ItemStack.of("minecraft:beef", 4, 64));
        Cook cook = new Cook(fire, 4, 0.4);
        WorkItem item = cook.open().get(0);

        cook.failed(item, ctx.self, ctx);
        ctx.percepts.time = SetUp.FAIL_COOLDOWN;
        cook.tick(ctx.percepts.time);
        assertTrue(cook.offerableTo(item, ctx.self, ctx), "one wait after one failure");

        cook.failed(item, ctx.self, ctx);
        ctx.percepts.time += SetUp.FAIL_COOLDOWN;
        cook.tick(ctx.percepts.time);
        assertFalse(cook.offerableTo(item, ctx.self, ctx), "twice that after a second, not every beat");

        Cook restored = Cook.restore((Cook.State) cook.snapshot(), ctx.percepts.time).orElseThrow();
        restored.failed(restored.open().get(0), ctx.self, ctx);
        long third = ctx.percepts.time;
        ctx.percepts.time += 3 * SetUp.FAIL_COOLDOWN;
        restored.tick(ctx.percepts.time);
        assertFalse(restored.offerableTo(restored.open().get(0), ctx.self, ctx),
                "a restart keeps the count: the third waits four, from " + third);
        ctx.percepts.time = third + 4 * SetUp.FAIL_COOLDOWN;
        restored.tick(ctx.percepts.time);
        assertTrue(restored.offerableTo(restored.open().get(0), ctx.self, ctx));
    }

    @Test
    void aFailureWhileTheCampfireStandsRestsOnlyThatMember() {
        ctx.claim(Campfire.POI, fire);
        ctx.percepts.inventory.set(0, dev.luizloyola.anima.core.inv.ItemStack.of("minecraft:beef", 4, 64));
        Cook cook = new Cook(fire, 12, 0.4);
        WorkItem item = cook.open().get(0);

        cook.failed(item, ctx.self, ctx);

        assertFalse(cook.finished());
        assertFalse(cook.offerableTo(item, ctx.self, ctx));
        assertTrue(cook.offerableTo(item, AgentId.random(), ctx));
    }
}
