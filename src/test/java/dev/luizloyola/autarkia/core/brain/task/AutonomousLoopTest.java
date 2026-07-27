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
 * The whole slice-1 loop, headless: an {@link Arbiter} of {@link EatInstinct} + {@link WanderInstinct}
 * in the mod's construction order, driven only by ticks and scripted actuator states.
 *
 * <p>{@link FakeConsumer} neither chews nor feeds the body, so the test plays the body: consumer to
 * FINISHED, nutrition applied to {@link dev.luizloyola.autarkia.core.person.Needs} as
 * {@code PersonItemConsumer} does in the field. Cost tolerance stays unbounded (bread is free ready
 * food); the gate itself is pinned in {@link EatLastResortTest}.
 */
class AutonomousLoopTest {

    private static final FoodValue BREAD = new FoodValue(5, 6.0F, false);

    private final FakeContext ctx = new FakeContext();
    private final Arbiter arbiter =
            new Arbiter(java.util.List.of(new EatInstinct(), new WanderInstinct(new Random(7), 8)));

    @Test
    void satedWandersInCyclesThenHungerPreemptsAndTheyEatThenWanderResumes() {
        // --- Phase 1: sated -> wander cycles ----------------------------------------------------
        assertEquals(20, ctx.percepts.needs.foodLevel(), "a fresh body spawns fed -> no eat pressure");

        arbiter.tick(ctx); // idle -> Wander (0.15) out-bids Eat (0.0)
        assertTrue(arbiter.describe().contains("wander 0.15 (active)"), arbiter.describe());

        // Most wander beats are idle-only (WALK_CHANCE) — tick through the standing-around until
        // the stream produces the first stroll. Seeded, so this is deterministic, never flaky.
        int guard = 0;
        while (ctx.mover.moveToCalls == 0 && guard++ < 10_000) {
            arbiter.tick(ctx);
        }
        assertEquals(1, ctx.mover.moveToCalls, "sated, they eventually stroll somewhere");

        ctx.mover.setState(MoveState.ARRIVED); 
        guard = 0;
        while (arbiter.executor().isBusy() && guard++ < 1000) {
            arbiter.tick(ctx); // GoTo SUCCESS, then the Idle pause counts down
        }
        assertFalse(arbiter.executor().isBusy(), "the first walking beat finished (stroll then pause)");

        // The living loop: re-granted beats keep coming until another one strolls.
        guard = 0;
        while (ctx.mover.moveToCalls == 1 && guard++ < 10_000) {
            arbiter.tick(ctx);
        }
        assertEquals(2, ctx.mover.moveToCalls, "wander re-grants itself — fresh legs keep coming");
        ctx.mover.setState(MoveState.MOVING); // hold this leg in flight for the preemption below

        // --- Phase 2: hunger preempts the wander ------------------------------------------------
        ctx.percepts.food("minecraft:bread", BREAD);
        ctx.percepts.inventory.set(0, ItemStack.of("minecraft:bread", 10, 64));
        ctx.percepts.needs.setFoodLevel(6);          // hunger 0.7 -> HUNGRY, over PREEMPT 0.6
        ctx.consumer.setState(ConsumeState.FINISHED); // fake body: a begun bite completes next read

        int stopsBefore = ctx.mover.stopCalls;
        arbiter.tick(ctx); // Eat (0.7) preempts the running wander leg and begins a bite
        assertEquals(1, ctx.consumer.beginCalls, "they interrupt wandering to eat");
        assertEquals(stopsBefore + 1, ctx.mover.stopCalls, "the wander leg's legs were released first");
        assertTrue(arbiter.describe().contains("eat") && arbiter.describe().contains("(active)"),
                arbiter.describe());
        ctx.percepts.needs.eat(BREAD.nutrition(), BREAD.saturation()); // body applies the first bite

        // --- Phase 3: they eat until sated, then wander resumes ---------------------------------
        int begins = ctx.consumer.beginCalls;
        guard = 0;
        while (ctx.mover.moveToCalls == 2 && guard++ < 10_000) {
            arbiter.tick(ctx);
            if (ctx.consumer.beginCalls > begins) { // a fresh bite started -> the body feeds
                begins = ctx.consumer.beginCalls;
                ctx.percepts.needs.eat(BREAD.nutrition(), BREAD.saturation());
            }
        }
        assertTrue(ctx.percepts.needs.foodLevel() >= 18,
                "they ate out of the hunger band (food " + ctx.percepts.needs.foodLevel() + ")");
        assertEquals(3, ctx.mover.moveToCalls, "sated again, wander takes back over — the loop closed");
        assertTrue(arbiter.describe().contains("wander 0.15 (active)"), arbiter.describe());
    }
}
