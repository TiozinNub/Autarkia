package dev.luizloyola.autarkia.core.person;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.agent.FoodValue;
import dev.luizloyola.anima.core.brain.gate.Act;
import dev.luizloyola.anima.core.brain.gate.Gate;
import dev.luizloyola.anima.core.brain.sense.BeingId;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.sense.Yields;
import dev.luizloyola.anima.core.brain.task.FakeContext;
import dev.luizloyola.anima.core.brain.task.FakePercepts;
import dev.luizloyola.anima.core.brain.task.Fight;
import dev.luizloyola.anima.core.brain.task.ReadyFood;
import dev.luizloyola.anima.core.inv.ItemSpec;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A Person hunts what drops food and is not in its danger table, and only once the gate opens the
 * act. The generator's guesses — 0 for every creature that is not a monster — are filled in by
 * hand, since only a running server makes them.
 */
class HuntingTest {

    private final FakeContext ctx = new FakeContext();

    @BeforeEach
    void setUp() {
        Map<String, Set<String>> drops = Map.of(
                "cow", Set.of("minecraft:beef", "minecraft:leather"),
                "polar_bear", Set.of("minecraft:cod"));
        Yields.install(new Yields.Lookup() {
            @Override
            public Set<String> of(String species) {
                return drops.getOrDefault(species, Set.of());
            }

            @Override
            public Set<String> species() {
                return drops.keySet();
            }
        });
        ctx.percepts.food("minecraft:beef", new FoodValue(3, 1.8F, false));
        ctx.percepts.food("minecraft:cod", new FoodValue(2, 0.4F, false));
        ReadyFood.install(ctx.percepts.foods());
        ctx.danger = PersonDanger.STORE.declared().withDerived(Map.of("cow", 0.0, "polar_bear", 0.0));
    }

    @AfterEach
    void tearDown() {
        Yields.install(null);
        ReadyFood.install(null);
        Gate.install(Gate.OPEN);
    }

    @Test
    void aCowIsPrey() {
        BeingId cow = BeingId.of(UUID.randomUUID());
        ctx.percepts.beings = List.of(FakePercepts.animalAt(cow, "cow", new Pos(6, 64, 0), 6.0));

        assertInstanceOf(Fight.class, Hunting.create(ReadyFood.SPEC).decompose(ctx).get(0));
    }

    @Test
    void aPolarBearIsNotThoughItDropsFish() {
        ctx.percepts.beings = List.of(FakePercepts.animalAt(BeingId.of(UUID.randomUUID()), "polar_bear",
                new Pos(6, 64, 0), 6.0));

        assertFalse(Hunting.create(ItemSpec.anyOf(Set.of("minecraft:cod"))).applicable(ctx),
                "the Person's own table fears it, whatever the generator guessed");
    }

    @Test
    void aBodyTheGateKeepsFromHuntingDoesNot() {
        assertTrue(Hunting.create(ReadyFood.SPEC).applicable(ctx), "nothing known: it would go looking");
        Gate.install(new Gate.Policy() {
            @Override
            public Optional<String> refuseItem(AgentId body, String itemId) {
                return Optional.empty();
            }

            @Override
            public Optional<String> refuseAct(AgentId body, Act act) {
                return act.equals(Hunting.ACT) ? Optional.of("not in the Wood Age") : Optional.empty();
            }
        });
        ctx.gate = Gate.viewFor(ctx.self, ctx.journal());

        assertFalse(Hunting.create(ReadyFood.SPEC).applicable(ctx));
    }
}
