package dev.luizloyola.autarkia.core.person;

import java.util.Locale;

/**
 * The Person's food physiology — vanilla's player {@code FoodData}, transcribed and verified
 * against the 26.1.2 bytecode. Vanilla gives a plain {@code LivingEntity} no FoodData, so the model
 * is ours. That is what makes it headless-testable.
 *
 * <p>Hunger is activity-driven like a player's: walking is free, only sprint meters, jumps, damage
 * and eventually attacking and mining bank exhaustion, so an idle Person does not get hungry
 * (ambient metabolism was tried and removed, decision 2026-07-22).
 *
 * <p>Two deviations, because a Person is an autonomous NPC:
 * <ul>
 *   <li><b>No Peaceful exemption.</b> Vanilla skips the food-level decrement on Peaceful; we drain
 *       at every difficulty.</li>
 *   <li><b>No difficulty clamp on starvation.</b> Vanilla clamps by difficulty (Easy stops at
 *       10 hp, Normal at 1 hp, only Hard kills); we always land the hit, so starvation is a real
 *       cause of death.</li>
 * </ul>
 *
 * <p>Needs are BODY state. The mod-layer Person owns this, ticks it once per game tick, feeds it
 * activity exhaustion ({@link #exhaust(float)} with the {@code EXHAUSTION_*} costs) and applies the
 * returned {@link TickResult} to the entity. The brain never writes here; it reads
 * {@link #hunger()} / {@link #band()} as bidding pressure.
 */
public final class Needs {
    // --- Vanilla FoodConstants, verbatim from the 26.1.2 jar ---
    /** Full food bar: 20 points (10 drumsticks). */
    public static final int MAX_FOOD = 20;
    /** Exhaustion accumulator cap; vanilla clamps here so a burst of activity can't bank unbounded drain. */
    public static final float MAX_EXHAUSTION = 40.0F;
    /** One drain step: every 4.0 accumulated exhaustion converts to -1 saturation (or -1 food). */
    public static final float EXHAUSTION_DROP = 4.0F;
    /** Normal (80-tick) regen requires food at or above this. */
    public static final int HEAL_LEVEL = 18;
    /** Sprinting requires food strictly above this (vanilla {@code hasEnoughFood}). */
    public static final int SPRINT_LEVEL = 6;
    /** Cadence, in ticks, of both normal regen and starvation hits. */
    public static final int HEALTH_TICK_COUNT = 80;
    /** Cadence of the fast saturated regen (full food bar + saturation to burn). */
    public static final int HEALTH_TICK_COUNT_SATURATED = 10;
    /** Exhaustion cost of one heal — regen burns food. */
    public static final float EXHAUSTION_HEAL = 6.0F;
    public static final float EXHAUSTION_JUMP = 0.05F;
    public static final float EXHAUSTION_SPRINT_JUMP = 0.2F;
    public static final float EXHAUSTION_SPRINT_PER_METER = 0.1F;
    public static final float EXHAUSTION_SWIM_PER_METER = 0.01F;
    /** Reserved: no attack behavior yet. */
    public static final float EXHAUSTION_ATTACK = 0.1F;
    /** Reserved: no block breaking yet. */
    public static final float EXHAUSTION_MINE = 0.005F;

    // State — vanilla FoodData defaults: a Person spawns fed like a fresh player spawns.
    private int foodLevel = MAX_FOOD;
    private float saturation = 5.0F;
    private float exhaustion;
    private int tickTimer;

    /**
     * What the body must apply this tick: a heal amount (0 = none) and whether a starvation hit
     * lands. Core reports; the BODY (mod-layer Person) actually heals the entity and deals the
     * damage — {@code net.minecraft} never leaks in here.
     */
    public record TickResult(float heal, boolean starve) {
        /** The common path — shared instance so a quiet tick allocates nothing. */
        public static final TickResult NONE = new TickResult(0.0F, false);
    }

    /**
     * One game tick — vanilla {@code FoodData.tick} minus the difficulty branches (see class doc):
     * drain accumulated exhaustion into saturation-then-food, then run exactly one of the vanilla
     * regen/starvation arms.
     *
     * @param naturalRegen the {@code naturalRegeneration} gamerule
     * @param isHurt       whether the entity is below max health (vanilla {@code isHurt()})
     * @return what the body must apply; {@link TickResult#NONE} on the quiet path
     */
    public TickResult tick(boolean naturalRegen, boolean isHurt) {
        if (exhaustion > EXHAUSTION_DROP) {
            exhaustion -= EXHAUSTION_DROP;
            if (saturation > 0.0F) {
                saturation = Math.max(saturation - 1.0F, 0.0F);
            } else {
                // DEVIATION: vanilla skips this decrement on Peaceful; we drain at every
                // difficulty — an NPC must need food everywhere.
                foodLevel = Math.max(foodLevel - 1, 0);
            }
        }
        if (naturalRegen && saturation > 0.0F && isHurt && foodLevel >= MAX_FOOD) {
            // Saturated fast regen.
            tickTimer++;
            if (tickTimer >= HEALTH_TICK_COUNT_SATURATED) {
                float fuel = Math.min(saturation, EXHAUSTION_HEAL); // vanilla's 6.0F literal
                exhaust(fuel);
                tickTimer = 0;
                return new TickResult(fuel / EXHAUSTION_HEAL, false);
            }
        } else if (naturalRegen && foodLevel >= HEAL_LEVEL && isHurt) {
            // Normal regen: heals half a heart.
            tickTimer++;
            if (tickTimer >= HEALTH_TICK_COUNT) {
                exhaust(EXHAUSTION_HEAL);
                tickTimer = 0;
                return new TickResult(1.0F, false);
            }
        } else if (foodLevel <= 0) {
            // Starvation. DEVIATION: no difficulty clamp — the hit always lands.
            tickTimer++;
            if (tickTimer >= HEALTH_TICK_COUNT) {
                tickTimer = 0;
                return new TickResult(0.0F, true);
            }
        } else {
            tickTimer = 0;
        }
        return TickResult.NONE;
    }

    /**
     * Eating — vanilla {@code FoodData.add}: food up by the nutrition (clamped to the bar),
     * saturation up by the given amount but never above the (new) food level.
     *
     * @param nutrition  the item's food points (bread = 5, steak = 8)
     * @param saturation the PRECOMPUTED saturation value (what {@code FoodProperties.saturation()}
     *                   supplies); from a modifier, use {@link #saturationByModifier(int, float)}
     */
    public void eat(int nutrition, float saturation) {
        foodLevel = Math.max(0, Math.min(foodLevel + nutrition, MAX_FOOD));
        this.saturation = Math.max(0.0F, Math.min(this.saturation + saturation, foodLevel));
    }

    /** Vanilla FoodConstants' formula: {@code nutrition * modifier * 2} (bread: 5 x 0.6 x 2 = 6.0). */
    public static float saturationByModifier(int nutrition, float saturationModifier) {
        return nutrition * saturationModifier * 2.0F;
    }

    /** Adds activity exhaustion (sprint meters, jumps, ...), capped at {@link #MAX_EXHAUSTION}. */
    public void exhaust(float amount) {
        exhaustion = Math.min(exhaustion + amount, MAX_EXHAUSTION);
    }

    /** Current food points, {@code 0..20}. */
    public int foodLevel() {
        return foodLevel;
    }

    /** Current saturation, {@code 0..foodLevel} — the buffer drained before food points are. */
    public float saturation() {
        return saturation;
    }

    /** Accumulated exhaustion, {@code 0..40} — drains one saturation/food unit per 4.0. */
    public float exhaustion() {
        return exhaustion;
    }

    /** The regen/starvation cadence counter — exposed for persistence. */
    public int tickTimer() {
        return tickTimer;
    }

    /** Sets food directly (load, debug commands), clamped to {@code [0, 20]}. */
    public void setFoodLevel(int v) {
        foodLevel = Math.max(0, Math.min(v, MAX_FOOD));
    }

    /** Sets saturation directly, clamped to {@code [0, foodLevel]} — the invariant eat/drain keep. */
    public void setSaturation(float v) {
        saturation = Math.max(0.0F, Math.min(v, foodLevel));
    }

    /** Sets exhaustion directly, clamped to {@code [0, 40]}. */
    public void setExhaustion(float v) {
        exhaustion = Math.max(0.0F, Math.min(v, MAX_EXHAUSTION));
    }

    /** Restores the cadence counter on load, so saving mid-cadence doesn't reset regen/starvation. */
    public void setTickTimer(int v) {
        tickTimer = v;
    }

    /** Vanilla {@code hasEnoughFood}: sprint allowed at food strictly above {@link #SPRINT_LEVEL}. */
    public boolean canSprint() {
        return foodLevel > SPRINT_LEVEL;
    }

    /**
     * Normalized hunger pressure for the brain, {@code 0..1} — 0 a full bar, 1 empty. Saturation
     * and exhaustion are metabolism internals the brain has no business reading.
     */
    public double hunger() {
        return 1.0 - foodLevel / (double) MAX_FOOD;
    }

    /**
     * Instinct-pressure bands on the {@link #hunger()} scale — where Eat starts bidding
     * (PECKISH), where it wins outright (HUNGRY), and where the arbiter's cost tolerance expands
     * to risky/expensive food methods (STARVING).
     */
    public enum Band { SATED, PECKISH, HUNGRY, STARVING }

    /**
     * The band this hunger falls in: {@code hunger() >= 0.85} STARVING, {@code >= 0.6} HUNGRY,
     * {@code >= 0.3} PECKISH, else SATED. Compared in food units so boundaries are exact.
     */
    public Band band() {
        if (foodLevel <= 3) return Band.STARVING;
        if (foodLevel <= 8) return Band.HUNGRY;
        if (foodLevel <= 14) return Band.PECKISH;
        return Band.SATED;
    }

    /** One-line debug summary, e.g. {@code "food 14/20 sat 2.3 exh 1.2 (peckish)"}. */
    public String describe() {
        return String.format(Locale.ROOT, "food %d/%d sat %.1f exh %.1f (%s)",
                foodLevel, MAX_FOOD, saturation, exhaustion, band().name().toLowerCase(Locale.ROOT));
    }
}
