package dev.luizloyola.autarkia.core.board;

/**
 * Everything a saved party project row needs to be told apart from another kind — the store's
 * view of a project, as opposed to {@link PartyProject}, the live one that thinks.
 *
 * <p>Sealed so the dispatching codec and {@link PartyProjects} are provably exhaustive over every
 * kind that exists, the same guarantee {@link WorkKey} gets from its own {@code permits}.
 *
 * <p>Every kind names itself in this clause, and a kind that is not here cannot be saved: that is
 * what makes the dispatching codec and {@link PartyProjects} provably exhaustive rather than
 * hopefully so.
 */
public sealed interface ProjectState permits ClearArea.State, Gather.State {

    /** The id this kind is saved and dispatched by — what {@link PartyProjects#byId} looks up. */
    String type();
}
