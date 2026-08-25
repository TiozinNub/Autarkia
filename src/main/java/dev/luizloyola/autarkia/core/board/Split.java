package dev.luizloyola.autarkia.core.board;

/**
 * How much of an outstanding project one member takes on a single trip — the policy a planner asks
 * before dispatching a hauler. A named strategy rather than a fixed number, so a richer split
 * (deadlines, material availability) can be posted later without touching the project or the store.
 */
public interface Split {

    /**
     * A stable name for this split, written into the party store and resolved through
     * {@link Splits} on load, like {@link Clearing#id()}.
     */
    String id();

    /**
     * How much of {@code remainder} one member should take on this trip.
     *
     * @param remainder how much of the project is still outstanding
     * @param free how many members currently hold no live item of this project
     * @return the size of one trip; never more than {@code remainder}
     */
    int tripFor(int remainder, int free);
}
