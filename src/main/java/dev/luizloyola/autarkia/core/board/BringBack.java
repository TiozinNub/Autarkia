package dev.luizloyola.autarkia.core.board;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.CompoundTask;
import dev.luizloyola.anima.core.brain.task.EnsureStore;
import dev.luizloyola.anima.core.brain.task.Method;
import dev.luizloyola.anima.core.brain.task.PutItems;
import dev.luizloyola.anima.core.brain.task.Task;
import dev.luizloyola.anima.core.inv.ItemSpec;
import java.util.List;

/**
 * Take a gather's load to the yard — whatever was got, if the fetch ran out short of its count. A
 * hunter who killed the last cow of a herd brings the meat home rather than carrying it about
 * while it looks for more. With nothing got there is no way, and the errand fails as it did before
 * this existed, without a walk home.
 */
public final class BringBack implements CompoundTask {

    private final ItemSpec spec;
    private final int count;
    private final Pos yard;
    private final List<Method> methods;

    public BringBack(ItemSpec spec, int count, Pos yard) {
        this.spec = spec;
        this.count = count;
        this.yard = yard;
        this.methods = List.of(new Deliver());
    }

    @Override
    public List<Method> methods() {
        return methods;
    }

    @Override
    public String describe() {
        return "bring the " + spec.name() + " to the yard";
    }

    public ItemSpec spec() {
        return spec;
    }

    public int count() {
        return count;
    }

    public Pos yard() {
        return yard;
    }

    private final class Deliver implements Method {
        @Override
        public boolean applicable(BrainContext ctx) {
            return ctx.percepts().inventory().count(spec.matcher()) > 0;
        }

        /**
         * Free: the load is the party's once got, and a hunt that ran 128 blocks out after a herd
         * would otherwise be priced out of coming home with it.
         */
        @Override
        public double estimateCost(BrainContext ctx) {
            return 0.0;
        }

        @Override
        public List<Task> decompose(BrainContext ctx) {
            return List.of(new EnsureStore(yard), PutItems.deposit(spec, count));
        }

        @Override
        public String describe() {
            return "carry it to the yard";
        }
    }
}
