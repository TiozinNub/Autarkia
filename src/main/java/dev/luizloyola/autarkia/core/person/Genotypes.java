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
 * The seed comes from the {@link dev.luizloyola.anima.core.agent.AgentId}, so the same person always
 * rolls the same face, and a child's seed can later be mixed from two parents' rather than drawn
 * fresh.
 *
 * <h2>Never uniform</h2>
 * A flat draw over a twelve-entry tone ladder gives as many of the palest as of the mid tones, which
 * reads as a random table. How a ladder is drawn is its own business (see {@code LadderSpec}):
 * authored {@code weights}, else a triangular hump over the order given.
 *
 * <p>Families stay uniform (a family is a choice with no middle for a bias to be about), so one
 * that wants odds states them per member.
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
        catalog.ladders().forEach((name, ladder) -> {
            if (ladder.size() > 0) {
                ladders.put(name, ladder.pick(random));
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

}
