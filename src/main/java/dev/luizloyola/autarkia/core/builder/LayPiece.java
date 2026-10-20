package dev.luizloyola.autarkia.core.builder;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.knowledge.BlockProbe;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.CompoundTask;
import dev.luizloyola.anima.core.brain.task.Method;
import dev.luizloyola.anima.core.brain.task.PlaceFrom;
import dev.luizloyola.anima.core.brain.task.Task;
import dev.luizloyola.autarkia.core.board.Stock;
import java.util.ArrayList;
import java.util.List;

/**
 * Place a run of a building's steps in order, each from a stand that reaches it. A step already
 * standing is passed over; the first that cannot be placed fails the piece. A step may be placed
 * against one before it in its piece ({@link BuildOrder}), so the rest are left for the next trip
 * rather than tried against a gap: 22 tried past one refusal each raised a pillar for nothing
 * (2026-10-20).
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
            List<Task> tasks = new ArrayList<>();
            for (Laying step : steps) {
                if (!standing(probe, step)) {
                    tasks.add(new PlaceFrom(step.placing(), step.also(), step.stand(), true, Stock.BRIDGING));
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
