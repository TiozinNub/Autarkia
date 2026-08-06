package dev.luizloyola.autarkia.core.person;

import dev.luizloyola.anima.core.appearance.catalog.Catalog;
import dev.luizloyola.anima.core.appearance.catalog.Choices;
import dev.luizloyola.anima.core.appearance.catalog.SlotSpec;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.random.RandomGenerator;

/**
 * Where a person's looks come from: one roll, from their own id, never again.
 *
 * <h2>Rolled from the agent, not from a moment</h2>
 * The seed comes from the {@link dev.luizloyola.anima.core.agent.AgentId}, so the same person
 * always rolls the same face — a genotype a body <em>has</em> rather than one a save file
 * remembers, and the hook heritability hangs on: a child's seed can be mixed from two parents'.
 *
 * <h2>Weighted, not uniform</h2>
 * A flat draw over a twelve-entry tone ladder gives as many of the palest as of the mid tones and
 * reads as a random table. Ladder positions are therefore <b>triangular</b> — the mean of two
 * uniform draws — so the middle is common and the ends rare. Family members stay uniform: a
 * hairstyle is a choice, not a spectrum.
 */
public final class Genotypes {
    private Genotypes() {}

    /**
     * Parameters that are never rolled because they are not <em>had</em>, they are <em>true right
     * now</em>: an expression, a blink, a word being spoken, the job someone is doing today. The
     * composer supplies these at the moment it composes.
     *
     * <p>{@code model} is here for a different reason — it is on the appearance already, chosen with
     * the gender it follows, and rolling it again here could contradict it.
     */
    private static final Set<String> STATE = Set.of("mood", "blink", "speaking", "job", "model");

    /**
     * Roll one person's genotype.
     *
     * @param seed    theirs and stable — see the class note on why it comes from the agent id
     * @param catalog supplies the ladders to place them on and says which slots may draw nothing
     * @param choices what each family actually holds, from {@link Choices} — so a settler can only be
     *                rolled into art that exists, and drawing a new PNG widens the roll with no edit
     */
    public static Look.Composed roll(long seed, Catalog catalog, Map<String, List<String>> choices) {
        RandomGenerator random = new java.util.SplittableRandom(seed);
        Map<String, Integer> ladders = new LinkedHashMap<>();
        catalog.ladders().forEach((name, entries) -> {
            if (!entries.isEmpty()) {
                ladders.put(name, triangular(random, entries.size()));
            }
        });

        Set<String> mayBeNone = omittable(catalog);
        Map<String, String> picks = new LinkedHashMap<>();
        choices.forEach((parameter, values) -> {
            if (STATE.contains(parameter) || values.isEmpty()) {
                return;
            }
            // A parameter only ever read by optional slots is allowed to go unanswered, and that is
            // how somebody ends up with no beard: the selector's placeholder stays unfilled, so the
            // slot draws nothing.
            if (mayBeNone.contains(parameter) && random.nextBoolean()) {
                return;
            }
            picks.put(parameter, values.get(random.nextInt(values.size())));
        });
        return new Look.Composed(ladders, picks);
    }

    /**
     * Parameters that no <em>required</em> slot reads.
     *
     * <p>Derived rather than listed, so a catalog marking a new slot optional needs no change here.
     * A parameter read by even one slot that always draws must always be answered: an unrolled
     * {@code shirt} is a settler with a hole where their torso goes, not "no shirt".
     */
    private static Set<String> omittable(Catalog catalog) {
        Set<String> optional = new LinkedHashSet<>();
        Set<String> required = new LinkedHashSet<>();
        for (SlotSpec slot : catalog.slots()) {
            for (var rule : slot.selector().rules()) {
                for (String texture : rule.textures()) {
                    (slot.optional() ? optional : required).addAll(Choices.placeholders(texture));
                }
            }
        }
        optional.removeAll(required);
        return optional;
    }

    /**
     * A ladder position, biased to the middle — the mean of two uniform draws.
     *
     * <p>Integer division truncates, tilting the result imperceptibly low; not worth correcting.
     */
    private static int triangular(RandomGenerator random, int size) {
        return size <= 1 ? 0 : (random.nextInt(size) + random.nextInt(size)) / 2;
    }
}
