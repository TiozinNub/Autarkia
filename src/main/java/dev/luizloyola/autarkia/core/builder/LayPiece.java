package dev.luizloyola.autarkia.core.builder;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.knowledge.BlockProbe;
import dev.luizloyola.anima.core.brain.task.CompoundTask;
import dev.luizloyola.anima.core.brain.task.Method;
import dev.luizloyola.anima.core.brain.task.PlaceFrom;
import dev.luizloyola.anima.core.brain.task.Task;
import dev.luizloyola.anima.core.brain.task.Try;
import java.util.ArrayList;
import java.util.List;

/**
 * Place a run of a building's steps in order, each from a stand that reaches it. A step already
 * standing is passed over; one that cannot be placed is left for the project to see, and the rest
 * go on.
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

    /** Whether the step's cell already holds what it places; a step placed by count is never. */
    static boolean standing(BlockProbe probe, Laying step) {
        return step.count() == 1 && step.block().equals(probe.idAt(step.cell().x(), step.cell().y(), step.cell().z()));
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
            List<Task> tasks = new ArrayList<>();
            for (Laying step : steps) {
                if (!standing(probe, step)) {
                    tasks.add(new Try(new PlaceFrom(step.placing(), step.also(), step.stand(), true)));
                }
            }
            return tasks;
        }

        @Override
        public String describe() {
            return "place each in the proved order";
        }
    }
}
