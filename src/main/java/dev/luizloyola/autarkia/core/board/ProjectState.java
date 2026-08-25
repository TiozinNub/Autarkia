package dev.luizloyola.autarkia.core.board;

/**
 * Everything a saved party project row needs to be told apart from another kind — the store's
 * view of a project, as opposed to {@link PartyProject}, the live one that thinks.
 *
 * <p>Sealed so the dispatching codec and {@link PartyProjects} are provably exhaustive over every
 * kind that exists, the same guarantee {@link WorkKey} gets from its own {@code permits}.
 *
 * <p><b>Permits {@link ClearArea.State} only for now.</b> A second project kind — the first is
 * {@code Gather.State} — adds itself to this clause the day it exists; until then a party's board
 * can hold exactly one shape of row, and this interface exists so that stops being true without
 * moving anything that already works.
 */
public sealed interface ProjectState permits ClearArea.State {

    /** The id this kind is saved and dispatched by — what {@link PartyProjects#byId} looks up. */
    String type();
}
