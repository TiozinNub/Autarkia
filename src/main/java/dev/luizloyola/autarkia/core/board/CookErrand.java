package dev.luizloyola.autarkia.core.board;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.CompoundTask;
import dev.luizloyola.anima.core.brain.task.CookAtCampfire;
import dev.luizloyola.anima.core.brain.task.Method;
import dev.luizloyola.anima.core.brain.task.RawFood;
import dev.luizloyola.anima.core.brain.task.ReadyFood;
import dev.luizloyola.anima.core.brain.task.Task;
import java.util.List;

/** Cook HOME's raw food at its campfire, then carry what came off to HOME's chest — one {@link Cook}. */
public final class CookErrand implements CompoundTask {

    private final Pos at;
    private final int count;
    private final List<Method> methods = List.of(new Way());

    public CookErrand(Pos at, int count) {
        this.at = at;
        this.count = count;
    }

    @Override
    public List<Method> methods() {
        return methods;
    }

    @Override
    public String describe() {
        return "cook at the campfire";
    }

    public Pos at() {
        return at;
    }

    public int count() {
        return count;
    }

    private final class Way implements Method {
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
            return List.of(new CookAtCampfire(at, RawFood.SPEC, count), new BringBack(ReadyFood.SPEC, count));
        }

        @Override
        public String describe() {
            return "cook it, then home";
        }
    }
}
