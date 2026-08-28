package dev.luizloyola.autarkia.core.board;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.inv.ItemSpec;

/**
 * How much of an outstanding project one body takes on a single trip — the policy a claimer asks
 * before taking work on. A named strategy rather than a fixed number, so a richer split
 * (deadlines, material availability) can be posted later without touching the project or the store.
 */
public interface Split {

    /**
     * A stable name for this split, written into the party store and resolved through
     * {@link Splits} on load, like {@link Clearing#id()}.
     */
    String id();

    /**
     * How much of {@code remainder} this asker takes on one trip.
     *
     * <p>The asker rather than a divisor: work is claimed, never handed out, so there is no count
     * of idle members to share against and no moment at which one would be known. A body that can
     * carry the lot takes the lot — the others are free to do something else (decision: Luiz,
     * 2026-08-28).
     *
     * @param remainder how much of the project is still outstanding
     * @param spec what the project wants. It travels WITH the question so that one call is the
     *             whole answer: sizing by what a body can carry means knowing which stacks already
     *             in its pack have headroom, and a caller left to re-clamp the result afterwards
     *             would be second-guessing the policy it just asked
     * @param asker the body doing the claiming, read live — nothing here is decided in advance
     * @return the size of one trip; never more than {@code remainder}, and {@code 0} for a body
     *         that should not be sent at all
     */
    int tripFor(int remainder, ItemSpec spec, BrainContext asker);
}
