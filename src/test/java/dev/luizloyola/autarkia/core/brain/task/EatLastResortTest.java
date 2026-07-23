package dev.luizloyola.autarkia.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.autarkia.core.brain.ToleranceCurve;
import dev.luizloyola.autarkia.core.brain.act.ConsumeState;
import dev.luizloyola.autarkia.core.inv.ItemStack;
import dev.luizloyola.autarkia.core.person.FoodValue;
import org.junit.jupiter.api.Test;

/**
 * Tier 2 of the eat policy — {@link EatLastResort}, the raw-food-and-treats half of the old
 * {@code EatFromInventory}, priced instead of band-gated. Raw-but-cookable food costs its
 * FORGONE nutrition (a raw potato baking to 5 costs 80); a {@code canAlwaysEat} treat is a flat
 * 80. The tolerance gate replaces the STARVING band-gate: held (compound FAILED, consumer
 * untouched) at the HUNGRY tolerance 60, eaten at ∞; free ready food always wins. Vanilla:
 * potato 1 → baked 5, bread 5, golden apple 4 ({@code canAlwaysEat}).
 */
class EatLastResortTest {

    private static final FoodValue POTATO = new FoodValue(1, 0.6F, false);
    private static final FoodValue BAKED_POTATO = new FoodValue(5, 6.0F, false);
    private static final FoodValue BREAD = new FoodValue(5, 6.0F, false);
    private static final FoodValue GOLDEN_APPLE = new FoodValue(4, 9.6F, true);

    private final FakeContext ctx = new FakeContext();
    private final TaskExecutor executor = new TaskExecutor();

    private void registerFoods() {
        ctx.percepts.food("minecraft:potato", POTATO);
        ctx.percepts.food("minecraft:bread", BREAD);
        ctx.percepts.food("minecraft:golden_apple", GOLDEN_APPLE);
    }

    /** Cooking a potato yields strictly more nutrition, so the potato reads as raw (tier 2). */
    private void potatoIsRaw() {
        ctx.percepts.cooked("minecraft:potato", BAKED_POTATO);
    }

    private int chosenSlot() {
        executor.run(new SatisfyHunger(), ctx);
        executor.tick(ctx);
        return ctx.consumer.lastSlot;
    }

    // --- pricing ---------------------------------------------------------------------------------

    @Test
    void constantsAreTheDocumentedPrices() {
        assertEquals(20.0, EatLastResort.OPPORTUNITY_PER_FORGONE_POINT);
        assertEquals(80.0, EatLastResort.TREAT_COST);
    }

    @Test
    void rawFoodIsPricedByForgoneNutrition() {
        registerFoods();
        potatoIsRaw();
        ctx.percepts.needs.setFoodLevel(3);
        ctx.percepts.inventory.set(4, ItemStack.of("minecraft:potato", 6, 64));
        // 20 per forgone point * (baked 5 - raw 1 = 4) = 80
        assertEquals(80.0, new EatLastResort().estimateCost(ctx), "raw potato: forgone-nutrition price");
    }

    @Test
    void treatIsPricedFlat() {
        registerFoods();
        ctx.percepts.needs.setFoodLevel(3);
        ctx.percepts.inventory.set(0, ItemStack.of("minecraft:golden_apple", 1, 64));
        assertEquals(80.0, new EatLastResort().estimateCost(ctx), "a golden apple is emergency supplies");
    }

    // --- the tolerance gate (replacing the STARVING band-gate) -----------------------------------

    @Test
    void rawPotatoHeldAtHungryToleranceThenEatenAtInfinity() {
        registerFoods();
        potatoIsRaw();
        ctx.percepts.needs.setFoodLevel(8); // merely hungry (band is now irrelevant — the number is)
        ctx.percepts.inventory.set(4, ItemStack.of("minecraft:potato", 6, 64));

        ctx.costTolerance = ToleranceCurve.HUNGRY_TOLERANCE; // 60 < the raw potato's 80
        executor.run(new SatisfyHunger(), ctx);
        executor.tick(ctx);
        assertFalse(executor.isBusy());
        assertEquals(0, ctx.consumer.beginCalls, "priced out -> the consumer is never touched");
        assertEquals("idle (last: satisfy hunger -> FAILED)", executor.describe(),
                "'saving the potatoes for the fire' — now enforced by price, not band");

        ctx.costTolerance = Double.POSITIVE_INFINITY; // starving / manual -> pay any price
        assertEquals(4, chosenSlot(), "unbounded tolerance eats the raw potato");
        ctx.consumer.setState(ConsumeState.FINISHED);
        executor.tick(ctx);
        assertEquals("idle (last: satisfy hunger -> SUCCESS)", executor.describe());
    }

    @Test
    void goldenAppleHeldAtHungryToleranceThenEatenAtInfinity() {
        registerFoods();
        ctx.percepts.needs.setFoodLevel(8);
        ctx.percepts.inventory.set(0, ItemStack.of("minecraft:golden_apple", 1, 64));

        ctx.costTolerance = ToleranceCurve.HUNGRY_TOLERANCE; // 60 < the treat's 80
        executor.run(new SatisfyHunger(), ctx);
        executor.tick(ctx);
        assertEquals(0, ctx.consumer.beginCalls);
        assertEquals("idle (last: satisfy hunger -> FAILED)", executor.describe());

        ctx.costTolerance = Double.POSITIVE_INFINITY;
        assertEquals(0, chosenSlot());
    }

    // --- cross-tier competition ------------------------------------------------------------------

    /** Free ready food out-competes the priced raw stack — the executor picks the cheaper method. */
    @Test
    void readyBreadBeatsRawPotatoByCost() {
        registerFoods();
        potatoIsRaw();
        ctx.percepts.needs.setFoodLevel(18); // missing 2: bread wastes 3, raw potato wastes 0
        ctx.percepts.inventory.set(1, ItemStack.of("minecraft:bread", 1, 64));
        ctx.percepts.inventory.set(3, ItemStack.of("minecraft:potato", 4, 64));
        assertEquals(1, chosenSlot(), "ready food out-competes raw on cost, whatever the tolerance");
    }

    @Test
    void readyBreadWinsEvenWhileStarving() {
        registerFoods();
        potatoIsRaw();
        ctx.percepts.needs.setFoodLevel(3); // starving -> tolerance would be infinite
        ctx.costTolerance = Double.POSITIVE_INFINITY;
        ctx.percepts.inventory.set(6, ItemStack.of("minecraft:potato", 2, 64));
        ctx.percepts.inventory.set(8, ItemStack.of("minecraft:bread", 1, 64));
        assertEquals(8, chosenSlot(), "the raw tier opens, but free ready bread still wins on cost");
    }

    // --- within-tier ordering & applicability ----------------------------------------------------

    @Test
    void lastResortTiesGoToTheLowestSlot() {
        registerFoods();
        potatoIsRaw();
        ctx.percepts.needs.setFoodLevel(3);
        ctx.percepts.inventory.set(9, ItemStack.of("minecraft:potato", 1, 64));
        ctx.percepts.inventory.set(4, ItemStack.of("minecraft:potato", 1, 64));
        assertEquals(4, chosenSlot(), "deterministic within tier 2 too — lowest slot");
    }

    /** Applicability no longer depends on the band — the old class refused raw food while merely
     * hungry — only on a raw/treat stack in hand with room to eat. */
    @Test
    void applicabilityIsBandIndependent() {
        registerFoods();
        potatoIsRaw();
        ctx.percepts.inventory.set(4, ItemStack.of("minecraft:potato", 6, 64));
        ctx.percepts.needs.setFoodLevel(8); // HUNGRY
        assertTrue(new EatLastResort().applicable(ctx), "applicable while hungry — the gate is price");
        ctx.percepts.needs.setFoodLevel(3); // STARVING
        assertTrue(new EatLastResort().applicable(ctx));
    }

    @Test
    void fullBarIsInapplicable() {
        registerFoods();
        potatoIsRaw();
        ctx.percepts.inventory.set(4, ItemStack.of("minecraft:potato", 6, 64)); // full bar (fresh body)
        assertFalse(new EatLastResort().applicable(ctx), "missing == 0 -> no reason to eat");
    }

    @Test
    void decomposeWithoutApplicableIsAContractViolation() {
        registerFoods();
        potatoIsRaw();
        ctx.percepts.needs.setFoodLevel(8); // hungry, but nothing carried
        EatLastResort method = new EatLastResort();
        assertFalse(method.applicable(ctx));
        assertThrows(IllegalStateException.class, () -> method.decompose(ctx));
    }
}
