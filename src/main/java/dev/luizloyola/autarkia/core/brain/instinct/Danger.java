package dev.luizloyola.autarkia.core.brain.instinct;

import dev.luizloyola.autarkia.core.config.Config;
import dev.luizloyola.autarkia.core.config.Knob;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * How much a species is worth fearing — the per-mob weight {@link FleeInstinct} multiplies into its
 * pressure. Not a {@link Knob}: that registry is a closed enum and entity ids are an open set, so
 * the weights are their own {@code danger} section in the config file, vanilla hostiles pre-seeded
 * below and every unnamed id falling to {@value #DEFAULT_KEY}. The GUI borrows each mob's own lang
 * key, so no entry needs a hand-written name.
 *
 * <p>The MODIFIER multipliers (held weapon, armor, mount, baby) are ordinary closed-set knobs — see
 * {@link Knob#DANGER_MELEE_MULT} and siblings. Same shape as {@link Config}: a process-wide
 * immutable map, swapped whole on install, volatile for off-thread readers. Keys are species
 * strings exactly as {@code Being.species} carries them (registry path, namespace-qualified only
 * when not {@code minecraft:}).
 */
public final class Danger {

    /** The weight every species the table doesn't name falls back to. */
    public static final String DEFAULT_KEY = "default";

    /**
     * The pre-seeded table. 1.0 is a zombie-grade nuisance; explosive, unblockable or bursty
     * threats rank above.
     */
    private static final Map<String, Double> DEFAULTS = defaults();

    /** Species whose THREAT out-reaches claws even bare-handed — extends the flee range the
     *  way a seen bow does; a heard-only skeleton still shoots from where it stands. */
    private static final Set<String> RANGED_SPECIES = Set.of(
            "skeleton", "stray", "bogged", "pillager", "witch", "blaze", "ghast",
            "shulker", "guardian", "elder_guardian", "llama", "trader_llama");

    private static volatile Map<String, Double> current = DEFAULTS;

    private Danger() {
    }

    /** The weight for one species — the table's entry, or the {@value #DEFAULT_KEY} fallback. */
    public static double weight(String species) {
        Map<String, Double> table = current;
        Double weight = table.get(species);
        if (weight != null) {
            return weight;
        }
        return table.getOrDefault(DEFAULT_KEY, 1.0);
    }

    /** Whether this species attacks from range regardless of what it visibly holds. */
    public static boolean rangedSpecies(String species) {
        return RANGED_SPECIES.contains(species);
    }

    /**
     * Installs the file's overrides over the seeded defaults — swap-whole like
     * {@link Config#install}. Unknown ids are VALID here (they are modded mobs, not typos);
     * the file layer only clamps the numbers.
     */
    public static void install(Map<String, Double> overrides) {
        Map<String, Double> merged = new LinkedHashMap<>(DEFAULTS);
        merged.putAll(overrides);
        current = Map.copyOf(merged);
    }

    /** Back to the seeded defaults — teardown for tests, and the "lost the file" path. */
    public static void reset() {
        current = DEFAULTS;
    }

    /** The whole live table, defaults included — what the file writes and the GUI lists. */
    public static Map<String, Double> table() {
        return current;
    }

    /** @see Knob#DANGER_MELEE_MULT */
    public static double meleeMult() {
        return Config.get().d(Knob.DANGER_MELEE_MULT);
    }

    /** @see Knob#DANGER_RANGED_MULT */
    public static double rangedMult() {
        return Config.get().d(Knob.DANGER_RANGED_MULT);
    }

    /** @see Knob#DANGER_ARMORED_MULT */
    public static double armoredMult() {
        return Config.get().d(Knob.DANGER_ARMORED_MULT);
    }

    /** @see Knob#DANGER_MOUNTED_MULT */
    public static double mountedMult() {
        return Config.get().d(Knob.DANGER_MOUNTED_MULT);
    }

    /** @see Knob#DANGER_BABY_MULT */
    public static double babyMult() {
        return Config.get().d(Knob.DANGER_BABY_MULT);
    }

    private static Map<String, Double> defaults() {
        Map<String, Double> table = new LinkedHashMap<>();
        table.put(DEFAULT_KEY, 1.0);
        // The everyday overworld night.
        table.put("zombie", 1.0);
        table.put("husk", 1.0);
        table.put("drowned", 1.1);
        table.put("zombie_villager", 1.0);
        table.put("skeleton", 1.2);
        table.put("stray", 1.2);
        table.put("bogged", 1.2);
        table.put("spider", 0.9);
        table.put("cave_spider", 1.1);
        table.put("creeper", 1.6);
        table.put("witch", 1.3);
        table.put("slime", 0.7);
        table.put("silverfish", 0.6);
        table.put("phantom", 1.1);
        // Raids and outposts.
        table.put("pillager", 1.3);
        table.put("vindicator", 1.5);
        table.put("evoker", 1.6);
        table.put("ravager", 1.8);
        table.put("vex", 1.2);
        // The angry neutrals.
        table.put("enderman", 1.5);
        table.put("zombified_piglin", 1.3);
        table.put("wolf", 1.0);
        table.put("bee", 0.5);
        table.put("iron_golem", 1.6);
        table.put("piglin", 1.2);
        table.put("polar_bear", 1.2);
        table.put("goat", 0.6);
        // Other dimensions, for the day they travel.
        table.put("blaze", 1.4);
        table.put("ghast", 1.3);
        table.put("magma_cube", 0.9);
        table.put("hoglin", 1.4);
        table.put("piglin_brute", 1.7);
        table.put("wither_skeleton", 1.5);
        table.put("guardian", 1.3);
        table.put("elder_guardian", 1.8);
        table.put("shulker", 1.2);
        table.put("breeze", 1.3);
        table.put("warden", 2.5);
        return Map.copyOf(table);
    }
}
