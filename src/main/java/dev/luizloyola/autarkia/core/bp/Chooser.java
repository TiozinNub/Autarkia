package dev.luizloyola.autarkia.core.bp;

import java.util.List;
import java.util.Set;
import java.util.random.RandomGenerator;

/**
 * Which way an {@code option} goes. A command's chooser is random; a settler's will prefer what is
 * in stock. Never asked about a {@code mix}, which the format defines as uniform over its terms.
 */
public interface Chooser {

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
     */
    record Choice(String label, Set<String> blocks, double weight) {
        public Choice {
            blocks = Set.copyOf(blocks);
        }
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
