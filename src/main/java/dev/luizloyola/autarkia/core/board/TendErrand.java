package dev.luizloyola.autarkia.core.board;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.CompoundTask;
import dev.luizloyola.anima.core.brain.task.Method;
import dev.luizloyola.anima.core.brain.task.Task;
import dev.luizloyola.anima.core.brain.task.UnloadFurnace;
import dev.luizloyola.anima.core.inv.ItemSpec;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Come back to a furnace, then carry what it made to HOME's chest — one {@link Tend}'s trip. */
public final class TendErrand implements CompoundTask {

    /** As much as a furnace's output slot holds. */
    static final int LOAD = 64;

    private final Pos at;
    private final ItemSpec output;
    private final @Nullable ItemSpec fuel;
    private final @Nullable Pos yard;
    private final List<Method> methods = List.of(new Way());

    public TendErrand(Pos at, ItemSpec output, @Nullable ItemSpec fuel, @Nullable Pos yard) {
        this.at = at;
        this.output = output;
        this.fuel = fuel;
        this.yard = yard;
    }

    @Override
    public List<Method> methods() {
        return methods;
    }

    @Override
    public String describe() {
        return "come back to the furnace";
    }

    public Pos at() {
        return at;
    }

    public ItemSpec output() {
        return output;
    }

    public @Nullable ItemSpec fuel() {
        return fuel;
    }

    public @Nullable Pos yard() {
        return yard;
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
            List<Task> steps = new ArrayList<>();
            steps.add(new UnloadFurnace(at, output, fuel));
            if (yard != null) {
                steps.add(new BringBack(output, LOAD, yard));
            }
            return steps;
        }

        @Override
        public String describe() {
            return "take it out, then home";
        }
    }
}
