package dev.luizloyola.autarkia.core.tree;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.task.AchieveTask;
import dev.luizloyola.anima.core.brain.task.BlocksToCross;
import dev.luizloyola.anima.core.brain.task.Method;
import dev.luizloyola.anima.core.brain.task.ObtainItem;
import dev.luizloyola.anima.core.brain.task.Task;
import java.util.List;

/**
 * {@link BlocksToCross} for a chop, which drives its own legs: a walk to a side stranded short of
 * blocks gets them, then the same chop walks that side again, building (Luiz, 2026-10-02). Each side
 * hands over once; a side that strands again is given up as any walk is, and the tree struck as
 * today.
 *
 * <p>An achieve-goal round the one chop, so a second side's hand-over is a fresh round rather than
 * a cure inside a cure: met when the tree is down, out of ways once the chop gives it up. Blocks
 * that cannot be had leave the chop to go on without them.
 */
public final class BlocksToFell implements AchieveTask {

    private final FellTree fell;
    private final List<Method> methods = List.of(new FetchThenFell(), new GoOn());

    public BlocksToFell(FellTree fell) {
        this.fell = fell;
        fell.wrapped();
    }

    /** The chop — what the codec writes down. */
    public FellTree fell() {
        return fell;
    }

    @Override
    public boolean satisfied(BrainContext ctx) {
        return fell.felled();
    }

    @Override
    public List<Method> methods() {
        return methods;
    }

    @Override
    public boolean standsIn() {
        return true;
    }

    @Override
    public String describe() {
        return "get blocks to cross to the tree at (" + fell.anchor().x() + ", " + fell.anchor().y()
                + ", " + fell.anchor().z() + ")";
    }

    /** The chop comes last in either way. */
    @Override
    public void rejoin(List<Task> subtasks) {
        int at = subtasks.size() - 1;
        if (at >= 0 && subtasks.get(at) instanceof FellTree) {
            subtasks.set(at, fell);
        }
    }

    private final class FetchThenFell implements Method {
        @Override
        public boolean applicable(BrainContext ctx) {
            return fell.blocksWanted() > 0;
        }

        @Override
        public double estimateCost(BrainContext ctx) {
            return 0.0;
        }

        @Override
        public List<Task> decompose(BrainContext ctx) {
            return List.of(new ObtainItem(BlocksToCross.SPEC, fell.takeBlocksWanted()), fell);
        }

        @Override
        public String describe() {
            return "fetch them, then walk building";
        }
    }

    private final class GoOn implements Method {
        @Override
        public boolean applicable(BrainContext ctx) {
            return fell.blocksWanted() == 0 && !fell.over();
        }

        @Override
        public double estimateCost(BrainContext ctx) {
            return 0.0;
        }

        @Override
        public List<Task> decompose(BrainContext ctx) {
            return List.of(fell);
        }

        @Override
        public String describe() {
            return "go on with the chop";
        }
    }
}
