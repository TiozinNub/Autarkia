package dev.luizloyola.autarkia.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.autarkia.core.brain.act.ConsumeState;
import dev.luizloyola.autarkia.core.inv.Inventory;
import dev.luizloyola.autarkia.core.inv.ItemStack;
import dev.luizloyola.autarkia.core.person.FoodValue;
import org.junit.jupiter.api.Test;

/**
 * End-to-end tests for the {@link SatisfyHunger} / {@link EatReadyFood} pair on the full
 * executor. The slot policy's tiers and the cost-tolerance gate are pinned in
 * {@link EatReadyFoodTest} / {@link EatLastResortTest}; here every test sets a hungry body,
 * since the policy refuses to eat at a full bar.
 */
class SatisfyHungerTest {

    private static final FoodValue BREAD = new FoodValue(5, 6.0F, false);
    private static final FoodValue STEAK = new FoodValue(8, 12.8F, false);

    private final FakeContext ctx = new FakeContext();
    private final TaskExecutor executor = new TaskExecutor();

    private void registerFoods() {
        ctx.percepts.food("minecraft:bread", BREAD);
        ctx.percepts.food("minecraft:cooked_beef", STEAK);
        // Hungry (food 8, missing 12): every plain food here fits without waste, so the policy
        // reduces to highest-nutrition-then-lowest-slot and the machinery stays the subject.
        ctx.percepts.needs.setFoodLevel(8);
    }

    @Test
    void decomposesToTheHighestNutritionSlot() {
        registerFoods();
        ctx.percepts.inventory.set(2, ItemStack.of("minecraft:bread", 3, 64));
        ctx.percepts.inventory.set(5, ItemStack.of("minecraft:cooked_beef", 1, 64));
        executor.run(new SatisfyHunger(), ctx);
        executor.tick(ctx); // expansion chooses the slot; ConsumeItem's first tick begins it
        assertEquals(5, ctx.consumer.lastSlot,
                "equal (zero) waste at food 8, so steak (8) beats bread (5) regardless of slot order");
        assertEquals(1, ctx.consumer.beginCalls);
    }

    @Test
    void nutritionTiesGoToTheLowestSlotIndex() {
        registerFoods();
        ctx.percepts.inventory.set(4, ItemStack.of("minecraft:bread", 1, 64));
        ctx.percepts.inventory.set(1, ItemStack.of("minecraft:bread", 1, 64));
        executor.run(new SatisfyHunger(), ctx);
        executor.tick(ctx);
        assertEquals(1, ctx.consumer.lastSlot, "deterministic tie-break: the lowest slot index");
    }

    /** Occupied means all 41 slots — offhand food is exactly how players carry a snack. */
    @Test
    void offhandFoodCounts() {
        registerFoods();
        ctx.percepts.inventory.setOffhand(ItemStack.of("minecraft:bread", 1, 64));
        executor.run(new SatisfyHunger(), ctx);
        executor.tick(ctx);
        assertEquals(Inventory.OFFHAND_SLOT, ctx.consumer.lastSlot);
    }

    @Test
    void endToEndEatsTheSteakAndSucceeds() {
        registerFoods();
        ctx.percepts.inventory.set(2, ItemStack.of("minecraft:bread", 3, 64));
        ctx.percepts.inventory.set(5, ItemStack.of("minecraft:cooked_beef", 1, 64));
        executor.run(new SatisfyHunger(), ctx);
        executor.tick(ctx); // begin(5), RUNNING
        ctx.consumer.setState(ConsumeState.CONSUMING);
        executor.tick(ctx); // mid-chew
        assertTrue(executor.isBusy());
        assertEquals("running: satisfy hunger > eat ready food > consume slot 5",
                executor.describe(), "the full expansion path, printable mid-bite");
        ctx.consumer.setState(ConsumeState.FINISHED);
        executor.tick(ctx); // SUCCESS bubbles: primitive -> method -> compound -> root
        assertFalse(executor.isBusy());
        assertEquals("idle (last: satisfy hunger -> SUCCESS)", executor.describe());
    }

    @Test
    void emptyInventoryFailsWithoutTouchingTheConsumer() {
        registerFoods();
        executor.run(new SatisfyHunger(), ctx);
        executor.tick(ctx); // EatFromInventory inapplicable -> no method -> root FAILED
        assertFalse(executor.isBusy());
        assertEquals(0, ctx.consumer.beginCalls, "no method expanded, so nothing ever acted");
        assertEquals("idle (last: satisfy hunger -> FAILED)", executor.describe());
    }

    /** Carrying things is not carrying food: stacks without a FoodValue don't make it applicable. */
    @Test
    void inedibleCarriedItemsDoNotCount() {
        registerFoods();
        ctx.percepts.inventory.set(0, ItemStack.of("minecraft:cobblestone", 12, 64));
        executor.run(new SatisfyHunger(), ctx);
        executor.tick(ctx);
        assertEquals(0, ctx.consumer.beginCalls);
        assertEquals("idle (last: satisfy hunger -> FAILED)", executor.describe());
    }

    @Test
    void describesReadAsTheDesignDocNamesThem() {
        assertEquals("satisfy hunger", new SatisfyHunger().describe());
        assertEquals("eat ready food", new EatReadyFood().describe());
        assertEquals("eat last resort", new EatLastResort().describe());
    }
}
