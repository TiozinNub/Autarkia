package dev.luizloyola.autarkia.core.board;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.board.WorkItem;
import java.util.ArrayList;
import java.util.List;

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
        // A lapsed hold goes back on offer before anything else reads the board this tick. The
        // holder here is always the owner, so this is how an agent preempted long enough stops
        // owing themselves an errand they abandoned.
        expire(ctx.percepts().time());
        closeFinished();
    }

    /**
     * The state of every project posted here, in post order — all of layer 3 that lives on a body
     * at all. Party boards keep their own, elsewhere.
     */
    public List<KeepStocked.State> snapshot() {
        List<KeepStocked.State> saved = new ArrayList<>();
        for (Project project : projects()) {
            if (project instanceof KeepStocked stocked) {
                saved.add(stocked.snapshot());
            }
        }
        return saved;
    }

    /**
     * Puts those rhythms back and re-establishes the owner's hold on anything that was claimed.
     *
     * @return the errand the owner is holding again, or empty — what an arbiter points back at
     */
    public java.util.Optional<WorkItem> restore(List<KeepStocked.State> saved, AgentId owner,
                                                long now) {
        java.util.Optional<WorkItem> held = java.util.Optional.empty();
        int i = 0;
        for (Project project : projects()) {
            if (!(project instanceof KeepStocked stocked) || i >= saved.size()) {
                continue;
            }
            KeepStocked.State state = saved.get(i++);
            stocked.restore(state);
            if (state.claimed() && stocked.openItem() != null) {
                reclaim(stocked.openItem(), owner, now);
                held = java.util.Optional.of(stocked.openItem());
            }
        }
        return held;
    }
}
