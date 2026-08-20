package dev.luizloyola.autarkia.core.board;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.CompoundTask;
import dev.luizloyola.anima.core.brain.task.Method;
import dev.luizloyola.anima.core.brain.task.PrimitiveTask;
import dev.luizloyola.anima.core.brain.task.PutAwaySurplus;
import dev.luizloyola.anima.core.brain.task.Task;
import java.util.List;

/**
 * Do the work, then take the load over if you are carrying enough to be worth the walk — what a
 * clear item's root becomes once its project has somewhere to put the wood.
 *
 * <p><b>The haul is a second STEP, not a second item.</b> The 2026-08-18 ruling holds the phase
 * machine, the ledger and the anchor-keyed items exactly as forest-verified; a haul errand of its
 * own would need a key, and a fungible one has no anchor to be keyed by. Riding inside the errand
 * also means it never competes for the wheel: the settler already holds this claim.
 *
 * <p><b>Below the line it costs nothing.</b> {@link PutAwaySurplus} is an achieve-goal, so with a
 * light pack it is satisfied on the spot and the tree is simply the whole errand. That is what makes
 * "haul when laden" fall out of the executor re-asking, instead of anything scheduling it.
 */
public final class HaulingErrand implements CompoundTask {

    private final Task work;
    private final Pos yard;
    private final int haulLine;
    private final List<Method> methods;

    public HaulingErrand(Task work, Pos yard, int haulLine) {
        this.work = work;
        this.yard = yard;
        this.haulLine = haulLine;
        this.methods = List.of(new WorkThenHaul());
    }

    @Override
    public List<Method> methods() {
        return methods;
    }

    @Override
    public String describe() {
        return workText() + ", then haul when laden";
    }

    /** {@code Task} is sealed over exactly these two, and describe() lives on each rather than on it. */
    private String workText() {
        return work instanceof CompoundTask compound
                ? compound.describe()
                : ((PrimitiveTask) work).describe();
    }

    public Task work() {
        return work;
    }

    public Pos yard() {
        return yard;
    }

    public int haulLine() {
        return haulLine;
    }

    private final class WorkThenHaul implements Method {
        @Override
        public boolean applicable(BrainContext ctx) {
            return true;
        }

        @Override
        public double estimateCost(BrainContext ctx) {
            // The work's price is the item's business; the haul is a condition, not a choice.
            return 0.0;
        }

        @Override
        public List<Task> decompose(BrainContext ctx) {
            return List.of(work, new PutAwaySurplus(yard, haulLine));
        }

        @Override
        public String describe() {
            return "work, then haul when laden";
        }
    }
}
