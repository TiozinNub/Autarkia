package dev.luizloyola.autarkia.core.board;

import dev.luizloyola.anima.core.brain.BrainContext;

/**
 * A {@link Project} that belongs to <em>one</em> agent and thinks with that agent's eyes — the
 * personal-board flavour, and why the cadence is not on {@code Project} itself.
 *
 * <p>A goal stated in terms of <em>this body's</em> pack can only be evaluated by reading that pack,
 * so its tick takes the owner's {@link BrainContext} and runs through the owner's
 * {@code BrainDriver}, not the entity-free host (decision: Luiz — compose, don't merge).
 */
public interface PersonalProject extends Project {

    /**
     * The project's own slow thinking, once per brain tick of its owner — post, withdraw, pace a
     * retry. Cheap on most ticks by contract: the owner calls this every tick regardless of who is
     * driving, so a project with a cadence keeps its own clock and returns early between beats.
     */
    void tick(BrainContext ctx);
}
