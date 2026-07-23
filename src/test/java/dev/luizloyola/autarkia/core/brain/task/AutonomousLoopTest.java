package dev.luizloyola.autarkia.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.autarkia.core.brain.Arbiter;
import dev.luizloyola.autarkia.core.brain.act.ConsumeState;
import dev.luizloyola.autarkia.core.brain.act.MoveState;
import dev.luizloyola.autarkia.core.brain.instinct.EatInstinct;
import dev.luizloyola.autarkia.core.brain.instinct.WanderInstinct;
import dev.luizloyola.autarkia.core.inv.ItemStack;
import dev.luizloyola.autarkia.core.person.FoodValue;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * The slice-1 loop headless: an {@link Arbiter} of {@link EatInstinct} + {@link WanderInstinct}
 * (the mod's construction order) on ticks and scripted actuator states alone. Sated she wanders in
 * cycles; hungry with bread, Eat preempts until hunger stops out-bidding idling, then wander
 * resumes.
 *
 * <p>{@link FakeConsumer} neither chews nor feeds the body, so the test does: it drives the
 * consumer to FINISHED and applies nutrition to
 * {@link dev.luizloyola.autarkia.core.person.Needs} as {@code PersonItemConsumer} does. Tolerance
 * is unbounded here (bread is free ready food); the gate is pinned in {@link EatLastResortTest}.
 */
class AutonomousLoopTest {

    private static final FoodValue BREAD = new FoodValue(5, 6.0F, false);

    private final FakeContext ctx = new FakeContext();
    private final Arbiter arbiter =
            new Arbiter(java.util.List.of(new EatInstinct(), new WanderInstinct(new Random(7), 8)));

    @Test
    void satedWandersInCyclesThenHungerPreemptsAndSheEatsThenWanderResumes() {
        // --- Phase 1: sated -> wander cycles ----------------------------------------------------
        assertEquals(20, ctx.percepts.needs.foodLevel(), "a fresh body spawns fed -> no eat pressure");

        arbiter.tick(ctx); // idle -> Wander (0.15) out-bids Eat (0.0); its GoTo issues a move
        assertEquals(1, ctx.mover.moveToCalls, "sated, she wanders");
        assertTrue(arbiter.describe().contains("wander 0.15 (active)"), arbiter.describe());

        ctx.mover.setState(MoveState.ARRIVED); 
        int guard = 0;
        while (arbiter.executor().isBusy() && guard++ < 1000) {
            arbiter.tick(ctx); // GoTo SUCCESS, then the Idle pause counts down
        }
        assertFalse(arbiter.executor().isBusy(), "the first wander leg finished (walk then pause)");

        arbiter.tick(ctx); // boundary re-grant -> a new wander root -> a fresh GoTo
        assertEquals(2, ctx.mover.moveToCalls, "wander re-grants itself — a fresh leg, the living loop");

        // --- Phase 2: hunger preempts the wander ------------------------------------------------
        ctx.percepts.food("minecraft:bread", BREAD);
        ctx.percepts.inventory.set(0, ItemStack.of("minecraft:bread", 10, 64));
        ctx.percepts.needs.setFoodLevel(6);          // hunger 0.7 -> HUNGRY, over PREEMPT 0.6
        ctx.consumer.setState(ConsumeState.FINISHED); // fake body: a begun bite completes next read

        int stopsBefore = ctx.mover.stopCalls;
        arbiter.tick(ctx); // Eat (0.7) preempts the running wander leg and begins a bite
        assertEquals(1, ctx.consumer.beginCalls, "she interrupts wandering to eat");
        assertEquals(stopsBefore + 1, ctx.mover.stopCalls, "the wander leg's legs were released first");
        assertTrue(arbiter.describe().contains("eat") && arbiter.describe().contains("(active)"),
                arbiter.describe());
        ctx.percepts.needs.eat(BREAD.nutrition(), BREAD.saturation()); // body applies the first bite

        // --- Phase 3: she eats until sated, then wander resumes ---------------------------------
        int begins = ctx.consumer.beginCalls;
        guard = 0;
        while (ctx.mover.moveToCalls == 2 && guard++ < 2000) {
            arbiter.tick(ctx);
            if (ctx.consumer.beginCalls > begins) { // a fresh bite started -> the body feeds
                begins = ctx.consumer.beginCalls;
                ctx.percepts.needs.eat(BREAD.nutrition(), BREAD.saturation());
            }
        }
        assertTrue(ctx.percepts.needs.foodLevel() >= 18,
                "she ate out of the hunger band (food " + ctx.percepts.needs.foodLevel() + ")");
        assertEquals(3, ctx.mover.moveToCalls, "sated again, wander takes back over — the loop closed");
        assertTrue(arbiter.describe().contains("wander 0.15 (active)"), arbiter.describe());
    }
}
