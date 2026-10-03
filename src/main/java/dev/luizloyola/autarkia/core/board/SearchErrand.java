package dev.luizloyola.autarkia.core.board;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.CompoundTask;
import dev.luizloyola.anima.core.brain.task.GoTo;
import dev.luizloyola.anima.core.brain.task.Method;
import dev.luizloyola.anima.core.brain.task.Task;
import java.util.List;

/**
 * An expedition's search, and home again if it found nothing. Either way the errand comes home
 * complete; the expedition reads what the searcher knows when it hears of it.
 */
public final class SearchErrand implements CompoundTask {

    private final SeekSource seek;
    private final List<Method> methods = List.of(new Seek(), new GoHome());

    public SearchErrand(SeekSource seek) {
        this.seek = seek;
    }

    public SeekSource seek() {
        return seek;
    }

    @Override
    public List<Method> methods() {
        return methods;
    }

    /** The search is the decomposition, so the restored one is the one that runs. */
    @Override
    public void rejoin(List<Task> subtasks) {
        if (!subtasks.isEmpty() && subtasks.get(0) instanceof SeekSource) {
            subtasks.set(0, seek);
        }
    }

    @Override
    public String describe() {
        return seek.describe();
    }

    private final class Seek implements Method {
        @Override
        public boolean applicable(BrainContext ctx) {
            return true;
        }

        @Override
        public double estimateCost(BrainContext ctx) {
            return 0;
        }

        @Override
        public List<Task> decompose(BrainContext ctx) {
            return List.of(seek);
        }

        @Override
        public String describe() {
            return "search";
        }
    }

    /** Nothing found: back to HOME, where the next search sets out from. */
    private final class GoHome implements Method {
        @Override
        public boolean applicable(BrainContext ctx) {
            return ctx.depot().isPresent();
        }

        @Override
        public double estimateCost(BrainContext ctx) {
            return 1;
        }

        @Override
        public List<Task> decompose(BrainContext ctx) {
            Pos home = ctx.depot().orElseThrow().hint();
            return List.of(new GoTo(home.x(), home.y(), home.z()));
        }

        @Override
        public String describe() {
            return "nothing found: home";
        }
    }
}
