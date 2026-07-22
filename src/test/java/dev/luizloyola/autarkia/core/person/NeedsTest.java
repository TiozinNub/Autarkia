package dev.luizloyola.autarkia.core.person;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The pure {@link Needs} model — the vanilla-FoodData drain/regen/starvation machine plus our two
 * deviations (see the class doc). The whole metabolism is ours, so the entire contract is testable
 * headless.
 */
class NeedsTest {

    /** Float arithmetic delta for exhaustion sums (sequential adds vs. one multiply). */
    private static final float EXHAUSTION_DELTA = 1e-4F;

    private static Needs atFood(int foodLevel) {
        Needs needs = new Needs();
        needs.setFoodLevel(foodLevel);
        needs.setSaturation(0.0F);
        return needs;
    }

    @Test
    void freshNeedsMatchVanillaSpawnDefaults() {
        Needs needs = new Needs();
        assertEquals(20, needs.foodLevel());
        assertEquals(5.0F, needs.saturation());
        assertEquals(0.0F, needs.exhaustion());
        assertEquals(0, needs.tickTimer());
        assertEquals(Needs.Band.SATED, needs.band());
        assertEquals(0.0, needs.hunger());
        assertTrue(needs.canSprint());
    }

    @Test
    void eatClampsFoodAtTheFullBar() {
        Needs needs = atFood(18);
        needs.eat(8, 12.8F); // steak
        assertEquals(20, needs.foodLevel());
        assertEquals(12.8F, needs.saturation(), EXHAUSTION_DELTA);
    }

    @Test
    void eatCapsSaturationAtTheNewFoodLevel() {
        Needs needs = atFood(1);
        needs.eat(4, 8.0F); // more saturation than the resulting bar can hold
        assertEquals(5, needs.foodLevel());
        assertEquals(5.0F, needs.saturation(), "saturation may never exceed the food level");
    }

    @Test
    void saturationByModifierIsNutritionTimesModifierTimesTwo() {
        assertEquals(6.0F, Needs.saturationByModifier(5, 0.6F), 1e-6F, "bread: 5 x 0.6 x 2");
        assertEquals(12.8F, Needs.saturationByModifier(8, 0.8F), 1e-6F, "steak: 8 x 0.8 x 2");
    }

    @Test
    void exhaustCapsAtMaxExhaustion() {
        Needs needs = new Needs();
        needs.exhaust(100.0F);
        assertEquals(Needs.MAX_EXHAUSTION, needs.exhaustion());
    }

    @Test
    void settersClampToTheirDocumentedRanges() {
        Needs needs = new Needs();
        needs.setFoodLevel(99);
        assertEquals(20, needs.foodLevel());
        needs.setFoodLevel(-5);
        assertEquals(0, needs.foodLevel());
        needs.setFoodLevel(10);
        needs.setSaturation(15.0F);
        assertEquals(10.0F, needs.saturation(), "saturation setter clamps to the food level");
        needs.setSaturation(-1.0F);
        assertEquals(0.0F, needs.saturation());
        needs.setExhaustion(50.0F);
        assertEquals(Needs.MAX_EXHAUSTION, needs.exhaustion());
        needs.setExhaustion(-1.0F);
        assertEquals(0.0F, needs.exhaustion());
        needs.setTickTimer(37);
        assertEquals(37, needs.tickTimer(), "cadence counter round-trips for persistence");
    }

    @Test
    void drainTakesSaturationBeforeFood() {
        Needs needs = new Needs();
        needs.setSaturation(1.0F);
        // The drain gate is a strict > (vanilla): exactly 4.0 banked sits still, so bank past it.
        needs.setExhaustion(Needs.EXHAUSTION_DROP + 0.1F);
        needs.tick(false, false);
        assertEquals(0.0F, needs.saturation());
        assertEquals(20, needs.foodLevel(), "food untouched while saturation remains");
        needs.setExhaustion(Needs.EXHAUSTION_DROP + 0.1F);
        needs.tick(false, false);
        assertEquals(19, needs.foodLevel(), "saturation gone -> the next drain hits food");
    }

    @Test
    void drainFloorsFoodAtZero() {
        Needs needs = atFood(0);
        needs.setExhaustion(Needs.EXHAUSTION_DROP);
        needs.tick(false, false);
        assertEquals(0, needs.foodLevel());
    }

    /**
     * The player-fidelity decision (2026-07-22, replacing an ambient-metabolism deviation): hunger
     * is purely activity-driven, so an idle Person's food bar sits still forever, like a player's.
     */
    @Test
    void idlePersonNeverGetsHungry() {
        Needs needs = new Needs();
        for (int i = 0; i < 50_000; i++) {
            assertEquals(Needs.TickResult.NONE, needs.tick(true, false));
        }
        assertEquals(20, needs.foodLevel());
        assertEquals(5.0F, needs.saturation(), "spawn saturation untouched without activity");
        assertEquals(0.0F, needs.exhaustion());
    }

    @Test
    void saturatedFastRegenHealsOnTheTenthTick() {
        Needs needs = new Needs();
        needs.setSaturation(3.0F);
        for (int i = 1; i <= 9; i++) {
            assertEquals(Needs.TickResult.NONE, needs.tick(true, true), "tick " + i + " is quiet");
        }
        assertEquals(9, needs.tickTimer());
        Needs.TickResult tenth = needs.tick(true, true);
        assertEquals(0.5F, tenth.heal(), 1e-6F, "min(sat 3, 6) / 6");
        assertFalse(tenth.starve());
        assertEquals(0, needs.tickTimer(), "cadence resets after the heal");
        assertEquals(3.0F, needs.exhaustion(), EXHAUSTION_DELTA,
                "the heal costs its fuel (exhaust 3.0)");
        assertEquals(3.0F, needs.saturation(), "saturation is spent via the drain, not directly");
    }

    @Test
    void normalRegenHealsOnTheEightiethTick() {
        Needs needs = atFood(18);
        for (int i = 1; i <= 79; i++) {
            assertEquals(Needs.TickResult.NONE, needs.tick(true, true), "tick " + i + " is quiet");
        }
        Needs.TickResult eightieth = needs.tick(true, true);
        assertEquals(1.0F, eightieth.heal(), 1e-6F);
        assertFalse(eightieth.starve());
        assertEquals(0, needs.tickTimer());
        assertEquals(6.0F, needs.exhaustion(), EXHAUSTION_DELTA, "the heal costs EXHAUSTION_HEAL");
    }

    @Test
    void noRegenCadenceWithoutTheGameruleOrWithoutBeingHurt() {
        Needs gameruleOff = new Needs();
        for (int i = 0; i < 50; i++) {
            assertEquals(Needs.TickResult.NONE, gameruleOff.tick(false, true));
        }
        assertEquals(0, gameruleOff.tickTimer(), "naturalRegen off -> the timer never starts");

        Needs unhurt = new Needs();
        for (int i = 0; i < 50; i++) {
            assertEquals(Needs.TickResult.NONE, unhurt.tick(true, false));
        }
        assertEquals(0, unhurt.tickTimer(), "not hurt -> the timer never starts");
    }

    @Test
    void starvationLandsExactlyOnEveryEightiethTick() {
        Needs needs = atFood(0);
        List<Integer> hits = new ArrayList<>();
        for (int i = 1; i <= 240; i++) {
            Needs.TickResult result = needs.tick(true, false);
            assertEquals(0.0F, result.heal(), "an empty bar never heals");
            if (result.starve()) {
                hits.add(i);
            }
        }
        assertEquals(List.of(80, 160, 240), hits);
    }

    @Test
    void saturatedRegenTakesPriorityOverNormalRegen() {
        // Food 20 qualifies for both arms (>= 18 too); saturation > 0 must pick the 10-tick one.
        Needs needs = new Needs();
        for (int i = 1; i <= 9; i++) {
            needs.tick(true, true);
        }
        Needs.TickResult tenth = needs.tick(true, true);
        assertEquals(Math.min(5.0F, 6.0F) / 6.0F, tenth.heal(), 1e-6F,
                "healed on the 10-tick saturated cadence, not the 80-tick one");
    }

    @Test
    void canSprintIsStrictlyAboveTheSprintLevel() {
        assertTrue(atFood(Needs.SPRINT_LEVEL + 1).canSprint());
        assertFalse(atFood(Needs.SPRINT_LEVEL).canSprint(), "vanilla hasEnoughFood is a strict >");
    }

    @Test
    void hungerIsInverseNormalizedFoodLevel() {
        assertEquals(0.0, atFood(20).hunger());
        assertEquals(0.5, atFood(10).hunger());
        assertEquals(1.0, atFood(0).hunger());
    }

    @Test
    void bandBoundariesAreExactInFoodUnits() {
        assertEquals(Needs.Band.SATED, atFood(15).band());
        assertEquals(Needs.Band.PECKISH, atFood(14).band(), "food 14 = hunger 0.3, already peckish");
        assertEquals(Needs.Band.PECKISH, atFood(9).band());
        assertEquals(Needs.Band.HUNGRY, atFood(8).band(), "food 8 = hunger 0.6, already hungry");
        assertEquals(Needs.Band.HUNGRY, atFood(4).band());
        assertEquals(Needs.Band.STARVING, atFood(3).band(), "food 3 = hunger 0.85, already starving");
        assertEquals(Needs.Band.STARVING, atFood(0).band());
    }

    @Test
    void describeReadsAsFoodSaturationExhaustionAndBand() {
        Needs needs = atFood(14);
        needs.setSaturation(2.3F);
        needs.setExhaustion(1.2F);
        assertEquals("food 14/20 sat 2.3 exh 1.2 (peckish)", needs.describe());
    }
}
