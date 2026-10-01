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

    @AfterEach
    void tearDown() {
        Gate.install(Gate.OPEN);
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
    void aFailureWhileTheCampfireStandsRestsOnlyThatMember() {
        ctx.claim(Campfire.POI, fire);
        Cook cook = new Cook(fire, 12, 0.4);
        WorkItem item = cook.open().get(0);

        cook.failed(item, ctx.self, ctx);

        assertFalse(cook.finished());
        assertFalse(cook.offerableTo(item, ctx.self, ctx));
        assertTrue(cook.offerableTo(item, AgentId.random(), ctx));
    }
}
