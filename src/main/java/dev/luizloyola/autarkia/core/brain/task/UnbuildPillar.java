package dev.luizloyola.autarkia.core.brain.task;

import dev.luizloyola.autarkia.core.brain.BrainContext;

/**
 * Un-builds whatever the body's standing ledger holds ({@code Scaffolder.placed()}), whoever
 * built it — the {@link dev.luizloyola.autarkia.core.brain.instinct.DescendInstinct}'s root.
 * Mechanics live in the shared {@link PillarDescent}. Never FAILED: an unreachable cell is left
 * standing and struck from the ledger, so it always converges on SUCCESS.
 */
public final class UnbuildPillar implements PrimitiveTask {
    private final PillarDescent descent = new PillarDescent();

    @Override
    public TaskStatus tick(BrainContext ctx) {
        return descent.tick(ctx);
    }

    @Override
    public void cancel(BrainContext ctx) {
        descent.cancel(ctx);
    }

    @Override
    public String describe() {
        return "un-build pillar";
    }
}
