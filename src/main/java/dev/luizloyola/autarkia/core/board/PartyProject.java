package dev.luizloyola.autarkia.core.board;

import dev.luizloyola.anima.core.brain.board.WorkItem;
import java.util.Optional;

/**
 * A {@link Project} that belongs to a <em>party</em> rather than to a body — the other flavour
 * {@link PersonalProject} is one of, and why the cadence lives on the flavour.
 *
 * <p><b>It thinks with nobody's eyes:</b> its tick takes a game time and nothing else, because it
 * runs in the server-side host ({@code PartyBoards}) on a staggered beat, must keep thinking when
 * every member is unloaded, and must not think once per member who ticks it; a member's context
 * arrives only when that member reports an outcome.
 *
 * <p><b>Its items have durable names</b> ({@link WorkKey}): a reload must hand two agents back their
 * own items, and identity does not survive a restart.
 */
public interface PartyProject extends Project {

    /**
     * The project's own slow thinking, once per host beat. Cheap by contract — the host ticks every
     * board on the same cadence, so a project with a rhythm of its own keeps its own clock and
     * returns early between beats.
     *
     * @param now current game time, the same clock every lease is measured against
     */
    void tick(long now);

    /**
     * This item's durable name, or empty if it is not one of ours. Called when the board is saved,
     * once per live lease.
     */
    Optional<WorkKey> keyOf(WorkItem item);

    /**
     * The item currently carrying that name, or empty when the project no longer offers it — a
     * target felled by somebody else while the world was down, a slice already reported. Called on
     * load, once per saved lease; an empty answer drops the hold.
     */
    Optional<WorkItem> itemFor(WorkKey key);
}
