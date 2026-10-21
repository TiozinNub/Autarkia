package dev.luizloyola.autarkia.core.builder;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.BreakBlock;
import dev.luizloyola.anima.core.brain.task.GoTo;
import dev.luizloyola.anima.core.brain.task.CompoundTask;
import dev.luizloyola.anima.core.brain.task.Method;
import dev.luizloyola.anima.core.brain.task.PlaceFrom;
import dev.luizloyola.anima.core.brain.task.Task;
import dev.luizloyola.autarkia.core.board.Stock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * One step of a {@link LayPiece}: nothing when it stands, else placed from a stand that reaches
 * it — what a player would break out of the cell first broken from a stand with a clear arm
 * ({@link Clearance}) — else passed over while the pack still holds its item. Out of the item it fails, and the piece
 * with it: every step after it would be refused the same way.
 */
public final class LayStep implements CompoundTask {

    private final Laying step;
    // Appended, never inserted: a saved plan resumes its method by index.
    private final List<Method> methods = List.of(new Stands(), new Lay(), new PassOver(), new ClearThenLay());

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
            return List.of(placeFrom());
        }

        @Override
        public String describe() {
            return "place it from a stand that reaches it";
        }
    }

    private PlaceFrom placeFrom() {
        return new PlaceFrom(step.placing(), step.also(), step.stand(), true, Stock.BRIDGING);
    }

    /** Break what holds the cell from a stand the arm gets through from, then place. */
    private final class ClearThenLay implements Method {
        @Override
        public boolean applicable(BrainContext ctx) {
            return carried(ctx) && Clearance.soft(ctx.percepts().blocks(), step.cell())
                    && Clearance.to(ctx, step.cell(), new HashSet<>(step.also())) != null;
        }

        @Override
        public double estimateCost(BrainContext ctx) {
            return 0.25;
        }

        @Override
        public List<Task> decompose(BrainContext ctx) {
            Clearance.Way way = Clearance.to(ctx, step.cell(), new HashSet<>(step.also()));
            List<Task> steps = new ArrayList<>();
            Pos stand = way.stand();
            if (!stand.equals(ctx.percepts().position())) {
                steps.add(new GoTo(stand.x(), stand.y(), stand.z()));
            }
            for (Pos leaf : way.inTheWay()) {
                steps.add(new BreakBlock(leaf.x(), leaf.y(), leaf.z()));
            }
            steps.add(new BreakBlock(step.cell().x(), step.cell().y(), step.cell().z()));
            steps.add(placeFrom());
            return steps;
        }

        @Override
        public String describe() {
            return "break what holds the cell, then place it";
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
