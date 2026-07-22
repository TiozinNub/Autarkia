package dev.luizloyola.autarkia.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.luizloyola.autarkia.core.brain.act.ConsumeState;
import dev.luizloyola.autarkia.core.inv.ItemStack;
import dev.luizloyola.autarkia.core.person.FoodValue;
import dev.luizloyola.autarkia.core.person.Needs;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link EatFromInventory}'s two-tier slot policy (see its class doc): least waste within a
 * tier, ready food over raw-but-cookable and {@code canAlwaysEat} treats, tier 2 gated on STARVING.
 * A refusal is {@code applicable() == false} plus a root FAILED that never reaches the consumer.
 * Vanilla numbers: potato 1, baked potato 5, bread 5, steak 8, golden apple 4 (canAlwaysEat).
 */
class EatFromInventoryTest {

    private static final FoodValue POTATO = new FoodValue(1, 0.6F, false);
    private static final FoodValue BAKED_POTATO = new FoodValue(5, 6.0F, false);
    private static final FoodValue BREAD = new FoodValue(5, 6.0F, false);
    private static final FoodValue STEAK = new FoodValue(8, 12.8F, false);
    private static final FoodValue GOLDEN_APPLE = new FoodValue(4, 9.6F, true);

    private final FakeContext ctx = new FakeContext();
    private final TaskExecutor executor = new TaskExecutor();

    private void registerFoods() {
        ctx.percepts.food("minecraft:potato", POTATO);
        ctx.percepts.food("minecraft:bread", BREAD);
        ctx.percepts.food("minecraft:cooked_beef", STEAK);
        ctx.percepts.food("minecraft:golden_apple", GOLDEN_APPLE);
    }

    /**
     * Adds the recipe signal: cooking a potato yields strictly more nutrition, so it reads as raw.
     * Without this call a potato is a small READY food — which the least-waste case below wants.
     */
    private void potatoIsRaw() {
        ctx.percepts.cooked("minecraft:potato", BAKED_POTATO);
    }

    /** Runs the eat tree one tick and reports which slot the expansion chose to consume. */
    private int chosenSlot() {
        executor.run(new SatisfyHunger(), ctx);
        executor.tick(ctx);
        return ctx.consumer.lastSlot;
    }

    /** Runs the eat tree one tick and asserts the policy refused: root FAILED, consumer untouched. */
    private void assertRefuses() {
        assertFalse(new EatFromInventory().applicable(ctx));
        executor.run(new SatisfyHunger(), ctx);
        executor.tick(ctx);
        assertEquals(0, ctx.consumer.beginCalls, "a refusal must never reach the consumer");
        assertEquals("idle (last: satisfy hunger -> FAILED)", executor.describe());
    }

    /** The potato-at-18 case: nearly full, the 1-point potato wastes nothing, the steak wastes 6. */
    @Test
    void leastWasteWinsWhenNearlyFull() {
        registerFoods(); // no potatoIsRaw(): this potato is a READY food, tier 1 like the steak
        ctx.percepts.needs.setFoodLevel(18); // missing 2
        ctx.percepts.inventory.set(2, ItemStack.of("minecraft:potato", 1, 64)); // waste 0
        ctx.percepts.inventory.set(5, ItemStack.of("minecraft:cooked_beef", 1, 64)); // waste 6
        assertEquals(2, chosenSlot(), "least waste first: the 1-point potato fits missing 2, "
                + "the steak would burn 6 points off the top of the bar");
    }

    @Test
    void equalZeroWasteFallsBackToHighestNutrition() {
        registerFoods(); // ready potato again
        ctx.percepts.needs.setFoodLevel(8); // missing 12 — everything fits, all waste 0
        ctx.percepts.inventory.set(2, ItemStack.of("minecraft:potato", 1, 64));
        ctx.percepts.inventory.set(5, ItemStack.of("minecraft:cooked_beef", 1, 64));
        assertEquals(5, chosenSlot(), "equal zero waste -> highest nutrition: the steak");
    }

    /** Tier precedence beats waste: ready bread (waste 3) over the zero-waste raw potato. */
    @Test
    void readyFoodOutranksRawEvenWhenRawWastesLess() {
        registerFoods();
        potatoIsRaw();
        ctx.percepts.needs.setFoodLevel(18); // missing 2: bread wastes 3, raw potato wastes 0
        ctx.percepts.inventory.set(1, ItemStack.of("minecraft:bread", 1, 64));
        ctx.percepts.inventory.set(3, ItemStack.of("minecraft:potato", 4, 64));
        assertEquals(1, chosenSlot(), "tier 1 nonempty -> raw food is not even in the running");
    }

    /** The headline refusal: merely HUNGRY with only raw potatoes, she holds them for the fire. */
    @Test
    void rawOnlyIsHeldWhileMerelyHungry() {
        registerFoods();
        potatoIsRaw();
        ctx.percepts.needs.setFoodLevel(8); // HUNGRY, not STARVING
        assertEquals(Needs.Band.HUNGRY, ctx.percepts.needs.band());
        ctx.percepts.inventory.set(4, ItemStack.of("minecraft:potato", 6, 64));
        assertRefuses();
    }

    @Test
    void rawOnlyIsEatenWhenStarving() {
        registerFoods();
        potatoIsRaw();
        ctx.percepts.needs.setFoodLevel(3); // STARVING — desperation covers the opportunity cost
        assertEquals(Needs.Band.STARVING, ctx.percepts.needs.band());
        ctx.percepts.inventory.set(4, ItemStack.of("minecraft:potato", 6, 64));
        assertEquals(4, chosenSlot());
        ctx.consumer.setState(ConsumeState.FINISHED);
        executor.tick(ctx);
        assertFalse(executor.isBusy());
        assertEquals("idle (last: satisfy hunger -> SUCCESS)", executor.describe(),
                "the raw potato is actually eaten, not merely selected");
    }

    /** Golden apples are emergency food, not lunch: canAlwaysEat is tier 2 like raw food. */
    @Test
    void treatOnlyIsHeldWhileMerelyHungry() {
        registerFoods();
        ctx.percepts.needs.setFoodLevel(8);
        ctx.percepts.inventory.set(0, ItemStack.of("minecraft:golden_apple", 1, 64));
        assertRefuses();
    }

    @Test
    void treatOnlyIsEatenWhenStarving() {
        registerFoods();
        ctx.percepts.needs.setFoodLevel(2);
        ctx.percepts.inventory.set(0, ItemStack.of("minecraft:golden_apple", 1, 64));
        assertEquals(0, chosenSlot());
        ctx.consumer.setState(ConsumeState.FINISHED);
        executor.tick(ctx);
        assertEquals("idle (last: satisfy hunger -> SUCCESS)", executor.describe());
    }

    /** missing == 0: nothing to restore, no reason to eat — carried food or not. */
    @Test
    void fullBarIsNeverApplicable() {
        registerFoods();
        assertEquals(Needs.MAX_FOOD, ctx.percepts.needs.foodLevel(), "a fresh body spawns fed");
        ctx.percepts.inventory.set(5, ItemStack.of("minecraft:cooked_beef", 3, 64));
        assertRefuses();
    }

    /** STARVING opens tier 2 but never reorders it above tier 1: ready bread still wins. */
    @Test
    void readyFoodStillWinsWhileStarving() {
        registerFoods();
        potatoIsRaw();
        ctx.percepts.needs.setFoodLevel(3);
        ctx.percepts.inventory.set(6, ItemStack.of("minecraft:potato", 2, 64));
        ctx.percepts.inventory.set(8, ItemStack.of("minecraft:bread", 1, 64));
        assertEquals(8, chosenSlot(), "tier 1 nonempty -> the STARVING gate is irrelevant");
    }

    /** The tie-break chain's last link inside a tier: same waste, same nutrition -> lowest slot. */
    @Test
    void fullTiesGoToTheLowestSlotWithinATier() {
        registerFoods();
        potatoIsRaw();
        ctx.percepts.needs.setFoodLevel(3);
        ctx.percepts.inventory.set(9, ItemStack.of("minecraft:potato", 1, 64));
        ctx.percepts.inventory.set(4, ItemStack.of("minecraft:potato", 1, 64));
        assertEquals(4, chosenSlot(), "deterministic within tier 2 as well");
    }

    @Test
    void decomposeWithoutApplicableIsAContractViolation() {
        registerFoods();
        ctx.percepts.needs.setFoodLevel(8);
        EatFromInventory method = new EatFromInventory();
        assertFalse(method.applicable(ctx));
        assertEquals(0.0, method.estimateCost(ctx), "carried food is the free baseline");
        assertThrows(IllegalStateException.class, () -> method.decompose(ctx));
    }
}
