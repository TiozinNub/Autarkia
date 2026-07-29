package dev.luizloyola.autarkia.core.person;

import dev.luizloyola.anima.core.brain.sense.DangerStore;
import dev.luizloyola.anima.core.brain.sense.DangerTable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * What frightens a settler, and how much. Anima keeps the machinery — the registry sweep, the
 * two-halves file, the resolution — and Autarkia keeps the opinions: a wolf is a nuisance to a
 * person and a mortal threat to a sheep.
 *
 * <p><b>Corrections, not a census.</b> Anima weights every entity type by guessing from its
 * category; below is only where a settler disagrees — the angry neutrals (a wolf and an iron golem
 * are {@code CREATURE} and {@code MISC}, and both will kill you) and the fast and explosive
 * monsters, ranked above the ordinary ones.
 */
public final class PersonDanger {

    /**
     * Species whose threat out-reaches claws whatever they are visibly holding — a heard-only
     * skeleton still shoots from where it stands, so this survives having no line of sight, which
     * is the gap the visible-weapon check cannot cover.
     */
    private static final Set<String> RANGED = Set.of(
            "skeleton", "stray", "bogged", "pillager", "witch", "blaze", "ghast",
            "shulker", "guardian", "elder_guardian", "llama", "trader_llama");

    /** The live table, regenerated at every server start and read through by every Person. */
    public static final DangerStore STORE = new DangerStore(
            new DangerTable(Map.of(), corrections(), RANGED));

    private PersonDanger() {
    }

    /** What a settler is like, on a subject the generator can only guess at. */
    private static Map<String, Double> corrections() {
        Map<String, Double> table = new LinkedHashMap<>();
        // What anything unnamed is worth, and what something that has attacked from cover is worth
        // before a face arrives.
        table.put(DangerTable.DEFAULT_KEY, 1.0);
        table.put(DangerTable.HOSTILE_KEY, 1.5);

        // The everyday overworld night. 1.0 is zombie-grade; explosive, unblockable or bursty
        // threats rank above it.
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
        // The angry neutrals: none of these is a MONSTER, and every one of them will kill a settler
        // that annoys it.
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
        return table;
    }
}
