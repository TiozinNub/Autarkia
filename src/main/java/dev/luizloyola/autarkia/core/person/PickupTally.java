package dev.luizloyola.autarkia.core.person;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;
import org.jspecify.annotations.Nullable;

/**
 * What a body has picked up by walking over it, summed into one journal line per window.
 *
 * <p>Felling and clearing drop items one at a time and the body walks over them across many
 * ticks: a line per tick was up to 1,443 {@code 1×minecraft:leaf_litter} lines per body in three
 * minutes on the forest (2026-10-02).
 *
 * <p>The window opens on the first catch and closes {@link #WINDOW_TICKS} later, whether or not
 * anything else is caught. The pending counts are saved with the body: a restart neither loses
 * them nor writes them twice.
 */
public final class PickupTally {

    /** One in-game minute: long enough to sum a felled tree's drops, short enough to place them. */
    public static final int WINDOW_TICKS = 1200;

    /** The pending counts and the game time the window opened. */
    public record State(Map<String, Integer> counts, long since) {
        public State {
            counts = Map.copyOf(counts);
        }
    }

    private final Map<String, Integer> counts = new LinkedHashMap<>();
    private long since;

    public void add(String id, int count, long now) {
        if (this.counts.isEmpty()) {
            this.since = now;
        }
        this.counts.merge(id, count, Integer::sum);
    }

    /**
     * The window's line once it has run its course, clearing the tally; otherwise null. A clock
     * that ran backwards closes the window too, rather than holding the counts forever.
     */
    public @Nullable String due(long now) {
        if (now - this.since < WINDOW_TICKS && now >= this.since) {
            return null;
        }
        return drain();
    }

    /** Whatever is pending, window or not, clearing the tally; null when nothing is. */
    public @Nullable String drain() {
        if (this.counts.isEmpty()) {
            return null;
        }
        StringJoiner line = new StringJoiner(", ", "picked up ", "");
        this.counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey(Comparator.naturalOrder())))
                .forEach(entry -> line.add(entry.getValue() + "×" + entry.getKey()));
        this.counts.clear();
        return line.toString();
    }

    public boolean isEmpty() {
        return this.counts.isEmpty();
    }

    public State snapshot() {
        return new State(this.counts, this.since);
    }

    public void restore(State state) {
        this.counts.clear();
        this.counts.putAll(state.counts());
        this.since = state.since();
    }
}
