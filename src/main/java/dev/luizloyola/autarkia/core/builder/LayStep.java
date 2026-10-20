package dev.luizloyola.autarkia.core.builder;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.task.CompoundTask;
import dev.luizloyola.anima.core.brain.task.Method;
import dev.luizloyola.anima.core.brain.task.PlaceFrom;
import dev.luizloyola.anima.core.brain.task.Task;
import dev.luizloyola.autarkia.core.board.Stock;
import java.util.List;

/**
 * One step of a {@link LayPiece}: nothing when it stands, else placed from a stand that reaches
 * it, else passed over while the pack still holds its item. Out of the item it fails, and the piece
 * with it: every step after it would be refused the same way.
 */
public final class LayStep implements CompoundTask {

    private final Laying step;
    private final List<Method> methods = List.of(new Stands(), new Lay(), new PassOver());

    public LayStep(Laying step) {
        this.step = step;
    }

    public Laying step() {
        return step;
    }

    @Override
    public List<Method> methods() {
        return methods;
    }

    @Override
    public String describe() {
        return "lay " + step.placing();
    }

    private boolean carried(BrainContext ctx) {
        return ctx.percepts().inventory().count(step.placing().itemId()) > 0;
    }

    private final class Stands implements Method {
        @Override
        public boolean applicable(BrainContext ctx) {
            return LayPiece.standing(ctx.percepts().blocks(), step);
        }

        @Override
        public double estimateCost(BrainContext ctx) {
            return 0.0;
        }

        @Override
        public List<Task> decompose(BrainContext ctx) {
            return List.of();
        }

        @Override
        public String describe() {
            return "it stands";
        }
    }

    private final class Lay implements Method {
        @Override
        public boolean applicable(BrainContext ctx) {
            return carried(ctx);
        }

        @Override
        public double estimateCost(BrainContext ctx) {
            return 0.5;
        }

        @Override
        public List<Task> decompose(BrainContext ctx) {
            return List.of(new PlaceFrom(step.placing(), step.also(), step.stand(), true, Stock.BRIDGING));
        }

        @Override
        public String describe() {
            return "place it from a stand that reaches it";
        }
    }

    /** What it is placed against may go up later in the piece: the second pass tries it again. */
    private final class PassOver implements Method {
        @Override
        public boolean applicable(BrainContext ctx) {
            return carried(ctx);
        }

        @Override
        public double estimateCost(BrainContext ctx) {
            return 1.0;
        }

        @Override
        public List<Task> decompose(BrainContext ctx) {
            return List.of();
        }

        @Override
        public String describe() {
            return "leave it for now";
        }
    }
}
