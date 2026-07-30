package dev.luizloyola.autarkia.core.board;

import dev.luizloyola.anima.core.brain.BrainContext;

/**
 * The board every agent has to themselves (decision: Luiz — <b>compose, don't merge</b>). A want
 * stated about <em>this body</em> must never land on somebody else's errand list, and what
 * guarantees that is a board nobody else can reach, not a flag on the item.
 *
 * <p>Its projects tick with the owner's eyes ({@link PersonalProject}); everything else is plain
 * {@link Board}, so the composite treats a one-member board like any other.
 */
public final class PersonalBoard extends Board {

    @Override
    public String label() {
        return "personal";
    }

    /**
     * One tick of the owner's own layer 3 — every posted {@link PersonalProject} thinks, then
     * anything satisfied is closed. Called every brain tick regardless of who is driving, since
     * posting is bookkeeping rather than action. A project that is not a {@code PersonalProject} is
     * carried but never ticked here.
     */
    @Override
    public void tick(BrainContext ctx) {
        for (Project project : projects()) {
            if (project instanceof PersonalProject personal) {
                personal.tick(ctx);
            }
        }
        closeFinished();
    }
}
