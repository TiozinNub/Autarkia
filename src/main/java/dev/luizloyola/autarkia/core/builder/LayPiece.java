package dev.luizloyola.autarkia.core.builder;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.knowledge.BlockProbe;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.CompoundTask;
import dev.luizloyola.anima.core.brain.task.Method;
import dev.luizloyola.anima.core.brain.task.PrimitiveTask;
import dev.luizloyola.anima.core.brain.task.Task;
import dev.luizloyola.anima.core.brain.task.TaskStatus;
import java.util.ArrayList;
import java.util.List;

/**
 * Place a run of a building's steps in order, each from a stand that reaches it ({@link LayStep}),
 * then once more in reverse for those that would not go in. The proved order runs a chain one way,
 * but a step refused for want of the one before it can often go against the one after: a roof row
 * whose first stair had no stand was left three short, and handed back, by a piece that stopped
 * there (2026-10-20). A step out of its item ends the piece, and a trip that placed none of them
 * fails ({@link WentIn}): passing over every step once counted as done, five times running.
 */
public final class LayPiece implements CompoundTask {

    private final List<Laying> steps;
    private final List<Method> methods = List.of(new Lay());

    public LayPiece(List<Laying> steps) {
        this.steps = List.copyOf(steps);
    }

    public List<Laying> steps() {
        return steps;
    }

    @Override
    public List<Method> methods() {
        return methods;
    }

    @Override
    public String describe() {
        return "build " + steps.size() + (steps.size() == 1 ? " block" : " blocks");
    }

    /** Whether the step's cell already holds what it places, as many as it counts. */
    static boolean standing(BlockProbe probe, Laying step) {
        Pos cell = step.cell();
        return step.standsAs(probe.idAt(cell.x(), cell.y(), cell.z()), probe.stateAt(cell.x(), cell.y(), cell.z()));
    }

    private final class Lay implements Method {
        @Override
        public boolean applicable(BrainContext ctx) {
            return true;
        }

        @Override
        public double estimateCost(BrainContext ctx) {
            return 0.0;
        }

        @Override
        public List<Task> decompose(BrainContext ctx) {
            BlockProbe probe = ctx.percepts().blocks();
            List<Laying> left = steps.stream().filter(step -> !standing(probe, step)).toList();
            List<Task> tasks = new ArrayList<>();
            left.forEach(step -> tasks.add(new LayStep(step)));
            for (int i = left.size() - 1; i >= 0; i--) {
                tasks.add(new LayStep(left.get(i)));
            }
            if (!left.isEmpty()) {
                tasks.add(new WentIn(left));
            }
            return tasks;
        }

        @Override
        public String describe() {
            return "place each in the proved order, then the rest backwards";
        }
    }

    /** Fails unless one of {@code steps} stands: a trip that placed nothing is no success. */
    public static final class WentIn implements PrimitiveTask {
        private final List<Laying> steps;

        public WentIn(List<Laying> steps) {
            this.steps = List.copyOf(steps);
        }

        public List<Laying> steps() {
            return steps;
        }

        @Override
        public TaskStatus tick(BrainContext ctx) {
            BlockProbe probe = ctx.percepts().blocks();
            return steps.stream().anyMatch(step -> standing(probe, step)) ? TaskStatus.SUCCESS : TaskStatus.FAILED;
        }

        @Override
        public void cancel(BrainContext ctx) {
        }

        @Override
        public String describe() {
            return "see that something went in";
        }

        @Override
        public String failureDetail() {
            return steps.size() == 1 ? "it would not go in" : "none of the " + steps.size() + " would go in";
        }
    }
}
