package dev.luizloyola.autarkia.core.board;

import dev.luizloyola.anima.core.brain.board.WorkItem;
import org.jspecify.annotations.Nullable;

/**
 * A personal project whose rhythm is saved with the body: {@link KeepStocked}'s shape, one errand
 * out at a time, re-minted from a flag on restore.
 */
public interface StandingProject extends PersonalProject {

    KeepStocked.State snapshot();

    /** Puts the rhythm back, re-minting the open errand if one was out. */
    void restore(KeepStocked.State state);

    /** The errand currently on offer, or null — so a reload can point an arbiter back at it. */
    @Nullable WorkItem openItem();
}
