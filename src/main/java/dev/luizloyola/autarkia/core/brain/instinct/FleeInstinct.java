package dev.luizloyola.autarkia.core.brain.instinct;

import dev.luizloyola.autarkia.core.brain.BrainContext;
import dev.luizloyola.autarkia.core.brain.sense.Being;
import dev.luizloyola.autarkia.core.brain.task.FleeStep;
import dev.luizloyola.autarkia.core.brain.task.Task;
import dev.luizloyola.autarkia.core.config.Config;
import dev.luizloyola.autarkia.core.config.Knob;
import java.util.random.RandomGenerator;

/**
 * The emergency drive, reading the PERCEIVED world rather than an omniscient scan: pressure comes
 * from the AGGRESSIVE beings this body has made out. A creeper behind a wall does not press, one
 * that spawned behind goes unnoticed until it sounds or crosses the cone, and a hit does not reveal
 * the shooter.
 *
 * <p><b>Per aggressive being:</b> a linear ramp from its REACH (extended for a shooter — seen bow,
 * drawn aim, or {@link Danger#rangedSpecies}) to contact over the last {@link #ramp()} blocks,
 * times {@link Danger#weight} and the visible-gear modifiers, times {@link #approachBonus()} when
 * it is CLOSING IN, capped at 1.0. Overall pressure is the MAX across beings — danger does not
 * stack; nothing aggressive perceived → 0.0.
 *
 * <p>{@link #failCooldown()} is ten ticks, not the {@link Instinct#DEFAULT_FAIL_COOLDOWN 100}: a
 * cornered Person must retry immediately with a freshly-rolled direction.
 */
public final class FleeInstinct implements Instinct {

    /** Beyond this straight-line distance a melee threat exerts no pressure at all. */
    public static double range() {
        return Config.get().d(Knob.FLEE_RANGE);
    }

    /** Pressure ramps linearly to full over this many blocks, ending at reach. */
    public static double ramp() {
        return Config.get().d(Knob.FLEE_RAMP);
    }

    /** @see Knob#FLEE_RANGED_RANGE_MULT */
    public static double rangedRangeMult() {
        return Config.get().d(Knob.FLEE_RANGED_RANGE_MULT);
    }

    /** @see Knob#FLEE_APPROACH_BONUS */
    public static double approachBonus() {
        return Config.get().d(Knob.FLEE_APPROACH_BONUS);
    }

    /** The emergency override of {@link Instinct#failCooldown()} — retry almost immediately. */
    public static final int FAIL_COOLDOWN = 10;

    private final RandomGenerator random;

    public FleeInstinct(RandomGenerator random) {
        this.random = random;
    }

    @Override
    public double pressure(BrainContext ctx) {
        double max = 0.0;
        for (Being being : ctx.percepts().beings()) {
            double pressure = pressureOf(being);
            if (pressure > max) {
                max = pressure;
            }
        }
        return max;
    }

    /** One being's contribution — public so tests and debug readouts price fear the same way. */
    public static double pressureOf(Being being) {
        if (!being.aggressive()) {
            return 0.0; // masked tiers read non-aggressive: unmade-out things exert nothing
        }
        double reach = range() * (ranged(being) ? rangedRangeMult() : 1.0);
        double ramped = clamp01((reach - being.distance()) / ramp());
        if (ramped == 0.0) {
            return 0.0;
        }
        double weight = Danger.weight(being.species()) * gearMult(being.gear());
        double pressure = ramped * weight;
        if (being.approaching()) {
            pressure *= approachBonus();
        }
        return Math.min(1.0, pressure);
    }

    /** Whether this threat's reach is a projectile's: seen ranged gear, a drawn aim, or a
     *  species that shoots bare-handed (blaze, ghast — no held item to see). */
    private static boolean ranged(Being being) {
        return being.gear().ranged() || being.activity() == Being.Activity.AIMING
                || Danger.rangedSpecies(being.species());
    }

    /** The visible-equipment story, multiplied — armored < with sword < …. */
    private static double gearMult(Being.Gear gear) {
        double mult = 1.0;
        if (gear.melee()) {
            mult *= Danger.meleeMult();
        }
        if (gear.ranged()) {
            mult *= Danger.rangedMult();
        }
        if (gear.armored()) {
            mult *= Danger.armoredMult();
        }
        if (gear.mounted()) {
            mult *= Danger.mountedMult();
        }
        if (gear.baby()) {
            mult *= Danger.babyMult();
        }
        return mult;
    }

    @Override
    public Task root(BrainContext ctx) {
        return new FleeStep(random);
    }

    @Override
    public int failCooldown() {
        return FAIL_COOLDOWN;
    }

    @Override
    public String describe() {
        return "flee";
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
