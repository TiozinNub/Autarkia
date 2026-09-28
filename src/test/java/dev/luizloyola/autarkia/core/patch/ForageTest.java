package dev.luizloyola.autarkia.core.patch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.agent.FoodValue;
import dev.luizloyola.anima.core.brain.gate.Act;
import dev.luizloyola.anima.core.brain.gate.Gate;
import dev.luizloyola.anima.core.brain.knowledge.AgentKnowledge;
import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.brain.knowledge.PoiMemory;
import dev.luizloyola.anima.core.brain.knowledge.Region;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.FakeContext;
import dev.luizloyola.anima.core.brain.task.GoTo;
import dev.luizloyola.anima.core.brain.task.ReadyFood;
import dev.luizloyola.anima.core.brain.task.Task;
import dev.luizloyola.anima.core.inv.ItemSpec;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Where ready food comes from: the nearest remembered patch that yields what is wanted, rested
 * after every trip, nobody else's, and only for a body the gate lets forage.
 */
class ForageTest {

    private final FakeContext ctx = new FakeContext();

    @BeforeEach
    void setUp() {
        ctx.percepts.food("minecraft:sweet_berries", new FoodValue(2, 0.4F, false));
        ctx.percepts.food("minecraft:melon_slice", new FoodValue(2, 1.2F, false));
        ReadyFood.install(ctx.percepts.foods());
        ctx.percepts.position = new Pos(0, 64, 0);
    }

    @AfterEach
    void tearDown() {
        ReadyFood.install(null);
        Gate.install(Gate.OPEN);
    }

    private void remember(PoiKind kind, Pos at) {
        ctx.knowledge.note(new PoiMemory(kind, at, Region.of(at), 1, false, 0L),
                AgentKnowledge.maxPerKind(ctx.profile()));
    }

    private static Forage forFood() {
        return new Forage(ReadyFood.SPEC);
    }

    @Test
    void withNoPatchKnownThereIsNoWay() {
        assertFalse(forFood().applicable(ctx));
    }

    @Test
    void aRememberedPatchIsAWayPricedByTheWalkAndTheWork() {
        remember(Patches.BERRIES, new Pos(12, 64, 0));

        assertTrue(forFood().applicable(ctx));
        assertEquals(12 + Forage.WORK, forFood().estimateCost(ctx), 1e-9);
    }

    @Test
    void theNearerPatchIsTheOneWalkedTo() {
        remember(Patches.BERRIES, new Pos(30, 64, 0));
        Pos melons = new Pos(-10, 64, 0);
        remember(Patches.MELONS, melons);

        PickPatch pick = assertInstanceOf(PickPatch.class, forFood().decompose(ctx).get(1));
        assertEquals(Patches.MELONS, pick.kind());
        assertEquals(melons, pick.anchor());
    }

    @Test
    void aTripWalksBesideThePatchAndPicksIt() {
        Pos berries = new Pos(12, 64, 0);
        remember(Patches.BERRIES, berries);

        List<Task> plan = forFood().decompose(ctx);

        GoTo walk = assertInstanceOf(GoTo.class, plan.get(0));
        assertTrue(Math.abs(walk.x() - berries.x()) <= 1 && Math.abs(walk.z() - berries.z()) <= 1
                && !(walk.x() == berries.x() && walk.z() == berries.z()), "beside it, not in the bush");
        assertEquals(berries, assertInstanceOf(PickPatch.class, plan.get(1)).anchor());
    }

    @Test
    void aPatchVisitedRestsBeforeItIsAWayAgain() {
        remember(Patches.BERRIES, new Pos(12, 64, 0));
        forFood().decompose(ctx);

        assertFalse(forFood().applicable(ctx), "what was ripe was picked, and a bare patch is no way");
        ctx.percepts.time = Forage.REST_TICKS;
        assertTrue(forFood().applicable(ctx), "two minutes on, bushes ripen again");
    }

    @Test
    void aPatchSomebodyElseIsWorkingIsLeftToThem() {
        Pos berries = new Pos(12, 64, 0);
        remember(Patches.BERRIES, berries);
        FakeContext other = new FakeContext();
        other.siteClaims = ctx.siteClaims;
        other.knowledge.note(new PoiMemory(Patches.BERRIES, berries, Region.of(berries), 1, false, 0L),
                AgentKnowledge.maxPerKind(other.profile()));
        other.percepts.position = new Pos(0, 64, 0);

        forFood().decompose(other);

        assertFalse(forFood().applicable(ctx), "two foragers at one patch pick half each, slowly");
    }

    @Test
    void aSpecWantingBerriesIsNoReasonToBreakMelons() {
        remember(Patches.MELONS, new Pos(5, 64, 0));
        Forage berriesOnly = new Forage(ItemSpec.anyOf(Set.of("minecraft:sweet_berries")));

        assertFalse(berriesOnly.applicable(ctx));
    }

    @Test
    void aBodyTheGateKeepsFromForagingDoesNot() {
        remember(Patches.BERRIES, new Pos(12, 64, 0));
        Gate.install(new Gate.Policy() {
            @Override
            public Optional<String> refuseItem(AgentId body, String itemId) {
                return Optional.empty();
            }

            @Override
            public Optional<String> refuseAct(AgentId body, Act act) {
                return act.equals(Forage.ACT) ? Optional.of("not in the Wood Age") : Optional.empty();
            }
        });
        ctx.gate = Gate.viewFor(ctx.self, ctx.journal());

        assertFalse(forFood().applicable(ctx));
    }
}
