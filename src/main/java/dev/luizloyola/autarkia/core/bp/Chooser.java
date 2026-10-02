package dev.luizloyola.autarkia.core.bp;

import java.util.List;
import java.util.Set;
import java.util.random.RandomGenerator;

/**
 * Which way an {@code option} goes. A command's chooser is random; a party's prefers what it has
 * ({@link #stocked}). Never asked about a {@code mix}, which the format defines as uniform over its
 * terms.
 */
public interface Chooser {

    /** What the planner names a choice among a blueprint's variant selections. */
    String VARIANTS = "variants";

    /**
     * @param what    the slot or legend entry choosing, as a reply names it
     * @param choices never empty
     * @return an index into {@code choices}
     */
    int choose(String what, List<Choice> choices);

    /**
     * One way an option can go.
     *
     * @param label  what a pin would say to force it; {@code $n} when it reads a slot that still mixes
     * @param blocks every block the file places if it goes this way
     * @param weight its share of a draw uniform over the union's terms
     * @param need   how many items the cells reading it take, 0 when not counted
     */
    record Choice(String label, Set<String> blocks, double weight, int need) {
        public Choice {
            blocks = Set.copyOf(blocks);
        }

        public Choice(String label, Set<String> blocks, double weight) {
            this(label, blocks, weight, 0);
        }
    }

    /**
     * The way the party has most of ({@link Stock#best}); among ties, and when it knows none of
     * them, {@code otherwise}. A variant selection is always {@code otherwise}'s: a bigger one names
     * more materials, and would win for that alone.
     */
    static Chooser stocked(Stock stock, Chooser otherwise) {
        return (what, choices) -> {
            List<Integer> best = what.equals(VARIANTS) ? List.of() : stock.best(choices);
            if (best.isEmpty()) {
                return otherwise.choose(what, choices);
            }
            if (best.size() == 1) {
                return best.get(0);
            }
            int picked = otherwise.choose(what, best.stream().map(choices::get).toList());
            return best.get(Math.max(0, Math.min(picked, best.size() - 1)));
        };
    }

    /** A draw by weight, so {@code option oak|nether_wood} is oak half the time. */
    static Chooser random(RandomGenerator random) {
        return (what, choices) -> {
            double total = 0;
            for (Choice choice : choices) {
                total += choice.weight();
            }
            double roll = random.nextDouble() * total;
            for (int i = 0; i < choices.size(); i++) {
                roll -= choices.get(i).weight();
                if (roll < 0) {
                    return i;
                }
            }
            return choices.size() - 1;
        };
    }
}
