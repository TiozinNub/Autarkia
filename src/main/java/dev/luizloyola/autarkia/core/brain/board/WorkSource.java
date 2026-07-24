package dev.luizloyola.autarkia.core.brain.board;

import dev.luizloyola.autarkia.core.brain.BrainContext;
import java.util.Optional;

/**
 * The board's face toward the arbiter — the second demand source beside the instinct list. The
 * arbiter asks what work is on offer and reports how the engagement ended; everything else stays
 * behind this seam, where slice 3's shared board will plug in. The mod layer may wrap a source to
 * observe transitions.
 */
public interface WorkSource {
    /** A source with nothing to offer, ever — the no-board arbiter for tests and manual rigs. */
    WorkSource NONE = new WorkSource() {
        @Override
        public Optional<WorkItem> bestAvailable(BrainContext ctx) {
            return Optional.empty();
        }

        @Override
        public void claimed(WorkItem item, BrainContext ctx) {
        }

        @Override
        public void completed(WorkItem item, BrainContext ctx) {
        }

        @Override
        public void failed(WorkItem item, BrainContext ctx) {
        }
    };

    /** The best unclaimed item currently on offer, if any. */
    Optional<WorkItem> bestAvailable(BrainContext ctx);

    /** The arbiter took the item — it is owed until completed, failed, or released. */
    void claimed(WorkItem item, BrainContext ctx);

    /** The item's root SUCCEEDED — the goal held; the board closes the item. */
    void completed(WorkItem item, BrainContext ctx);

    /** The item's root FAILED — the board unclaims it and paces the retry. */
    void failed(WorkItem item, BrainContext ctx);
}
