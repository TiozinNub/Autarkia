package dev.luizloyola.autarkia.core.board;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.task.CompoundTask;
import dev.luizloyola.anima.core.brain.task.Method;
import dev.luizloyola.anima.core.brain.task.PrimitiveTask;
import dev.luizloyola.anima.core.brain.task.PutAwaySurplus;
import dev.luizloyola.anima.core.brain.task.Task;
import java.util.List;
import java.util.function.BooleanSupplier;

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
 *
 * <p><b>The last tree takes everything.</b> When the project has nothing left to hand out, the load
 * goes home whatever its size, or it would stay in the pack for good.
 */
public final class HaulingErrand implements CompoundTask {

    private final Task work;
    private final int haulLine;
    /** Whether the project has more to hand out; asked after the work, not when the errand starts. */
    private final BooleanSupplier workLeft;
    private final List<Method> methods;

    public HaulingErrand(Task work, int haulLine, BooleanSupplier workLeft) {
        this.work = work;
        this.haulLine = haulLine;
        this.workLeft = workLeft;
        this.methods = List.of(new WorkThenHaul());
    }

    /**
     * What a save hands back: the project's live answer is not data, so until it re-grants the
     * errand the job is taken to go on, and the load waits for the line.
     */
    public static HaulingErrand restored(Task work, int haulLine) {
        return new HaulingErrand(work, haulLine, () -> true);
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

    /** The work comes first, before the haul. */
    @Override
    public void rejoin(List<Task> subtasks) {
        if (!subtasks.isEmpty() && subtasks.get(0).getClass() == work.getClass()) {
            subtasks.set(0, work);
        }
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
            return List.of(work, new PutAwaySurplus(haulLine, workLeft));
        }

        @Override
        public String describe() {
            return "work, then haul when laden";
        }
    }
}
