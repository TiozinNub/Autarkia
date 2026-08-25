package dev.luizloyola.autarkia.core.board;

/**
 * Shares an outstanding project evenly across whoever is free — the only {@link Split} in v1.
 *
 * <p>{@code trip = min(remainder, clamp(ceil(remainder / free), MIN_TRIP, MAX_TRIP))}: split the
 * remainder evenly across the free members, keep that share inside what one trip is worth, and
 * never send anyone for more than is actually left.
 */
public final class EvenSplit implements Split {

    /** The one instance — registered at bootstrap and meant by the store and the project. */
    public static final EvenSplit INSTANCE = new EvenSplit();

    /** The smallest payload worth a walk — below it, splitting further only adds trips, not speed. */
    public static final int MIN_TRIP = 16;

    /** The most worth carrying before putting it down — caps a trip regardless of how few are free. */
    public static final int MAX_TRIP = 64;

    private EvenSplit() {
    }

    @Override
    public String id() {
        return "even";
    }

    @Override
    public int tripFor(int remainder, int free) {
        if (remainder <= 0 || free <= 0) {
            return 0;
        }
        int evenShare = (remainder + free - 1) / free;
        int trip = Math.min(MAX_TRIP, Math.max(MIN_TRIP, evenShare));
        // The outer min: without it, a nearly-finished job with five left would still send someone
        // for a full MIN_TRIP and overshoot by eleven.
        return Math.min(remainder, trip);
    }
}
