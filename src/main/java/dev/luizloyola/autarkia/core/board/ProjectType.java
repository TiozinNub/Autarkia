package dev.luizloyola.autarkia.core.board;

import java.util.Optional;

/**
 * What a kind of party project IS: how its saved {@link ProjectState} rebuilds into something that
 * can hold errands and think again. One per state kind, registered in {@link PartyProjects}.
 *
 * <p>A layer above {@link Felling}: a {@code Felling} says what to clear inside a
 * {@link FellTrees}; this says what a party project itself can BE, the same distinction
 * {@link PartyProjects} draws from {@link Fellings}.
 */
public interface ProjectType {

    /** The id this kind is saved and looked up by — matches {@link ProjectState#type()}. */
    String id();

    /**
     * Rebuilds a live project from its saved state, or empty when the state names something this
     * type cannot answer for (an unknown {@link Felling} id, today) — a real failure for the store
     * to count, never a row to drop quietly.
     */
    Optional<? extends PartyProject> restore(ProjectState state, long now);
}
