package dev.luizloyola.autarkia.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.luizloyola.autarkia.core.inv.ItemStack;
import dev.luizloyola.autarkia.core.person.FoodValue;
import dev.luizloyola.autarkia.core.person.Needs;
import org.junit.jupiter.api.Test;

/**
 * Pins tier 1 of the eat policy — {@link EatReadyFood}, the plain-ready-food half of the old
 * {@code EatFromInventory}: within-tier ordering (least waste → highest nutrition → lowest slot),
 * the always-{@code 0.0} cost that makes ready food admissible at any tolerance, and the
 * {@code missing == 0} refusal. Vanilla numbers: potato 1, bread 5, steak 8. No cooked form is
 * registered for the potato here, so it is a small READY food (tier 1) — its raw-tier behavior
 * lives in {@link EatLastResortTest}.
 */
class EatReadyFoodTest {

    private static final FoodValue POTATO = new FoodValue(1, 0.6F, false);
    private static final FoodValue BREAD = new FoodValue(5, 6.0F, false);
    private static final FoodValue STEAK = new FoodValue(8, 12.8F, false);

    private final FakeContext ctx = new FakeContext();
    private final TaskExecutor executor = new TaskExecutor();

    private void registerFoods() {
        ctx.percepts.food("minecraft:potato", POTATO);
        ctx.percepts.food("minecraft:bread", BREAD);
        ctx.percepts.food("minecraft:cooked_beef", STEAK);
    }

    /** Runs the eat tree one tick and reports which slot the expansion chose to consume. */
    private int chosenSlot() {
        executor.run(new SatisfyHunger(), ctx);
        executor.tick(ctx);
        return ctx.consumer.lastSlot;
    }

    @Test
    void leastWasteWinsWhenNearlyFull() {
        registerFoods();
        ctx.percepts.needs.setFoodLevel(18); // missing 2
        ctx.percepts.inventory.set(2, ItemStack.of("minecraft:potato", 1, 64)); // waste 0
        ctx.percepts.inventory.set(5, ItemStack.of("minecraft:cooked_beef", 1, 64)); // waste 6
        assertEquals(2, chosenSlot(), "the 1-point potato fits missing 2; the steak would burn 6");
    }

    @Test
    void equalZeroWasteFallsBackToHighestNutrition() {
        registerFoods();
        ctx.percepts.needs.setFoodLevel(8); // missing 12 — everything fits, all waste 0
        ctx.percepts.inventory.set(2, ItemStack.of("minecraft:potato", 1, 64));
        ctx.percepts.inventory.set(5, ItemStack.of("minecraft:cooked_beef", 1, 64));
        assertEquals(5, chosenSlot(), "equal zero waste -> highest nutrition: the steak");
    }

    @Test
    void nutritionTiesGoToTheLowestSlot() {
        registerFoods();
        ctx.percepts.needs.setFoodLevel(8);
        ctx.percepts.inventory.set(4, ItemStack.of("minecraft:bread", 1, 64));
        ctx.percepts.inventory.set(1, ItemStack.of("minecraft:bread", 1, 64));
        assertEquals(1, chosenSlot(), "same waste, same nutrition -> lowest slot");
    }

    @Test
    void costIsAlwaysZero() {
        registerFoods();
        ctx.percepts.needs.setFoodLevel(8);
        ctx.percepts.inventory.set(2, ItemStack.of("minecraft:bread", 1, 64));
        assertEquals(0.0, new EatReadyFood().estimateCost(ctx), "ready food is the free baseline");
    }

    /** Cost 0 means ready food is admitted even at the zero tolerance of a barely-peckish Person. */
    @Test
    void readyFoodIsEatenEvenAtZeroTolerance() {
        registerFoods();
        ctx.percepts.needs.setFoodLevel(18); // hunger 0.1 -> the arbiter would set tolerance 0.0
        ctx.costTolerance = 0.0;
        ctx.percepts.inventory.set(3, ItemStack.of("minecraft:bread", 1, 64));
        assertEquals(3, chosenSlot(), "free food passes a zero cost ceiling");
    }

    @Test
    void fullBarIsInapplicable() {
        registerFoods();
        assertEquals(Needs.MAX_FOOD, ctx.percepts.needs.foodLevel(), "a fresh body spawns fed");
        ctx.percepts.inventory.set(5, ItemStack.of("minecraft:cooked_beef", 3, 64));
        assertFalse(new EatReadyFood().applicable(ctx), "missing == 0 -> nothing to restore");
        executor.run(new SatisfyHunger(), ctx);
        executor.tick(ctx);
        assertEquals(0, ctx.consumer.beginCalls, "a refusal never reaches the consumer");
        assertEquals("idle (last: satisfy hunger -> FAILED)", executor.describe());
    }

    @Test
    void decomposeWithoutApplicableIsAContractViolation() {
        registerFoods();
        ctx.percepts.needs.setFoodLevel(8); // hungry, but nothing edible carried
        EatReadyFood method = new EatReadyFood();
        assertFalse(method.applicable(ctx));
        assertThrows(IllegalStateException.class, () -> method.decompose(ctx));
    }
}
