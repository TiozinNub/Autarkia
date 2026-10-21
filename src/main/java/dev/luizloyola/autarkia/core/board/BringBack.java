package dev.luizloyola.autarkia.core.board;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.CompoundTask;
import dev.luizloyola.anima.core.brain.task.EnsureStore;
import dev.luizloyola.anima.core.brain.task.EnsureTable;
import dev.luizloyola.anima.core.brain.task.GoTo;
import dev.luizloyola.anima.core.brain.task.Method;
import dev.luizloyola.anima.core.brain.task.PutItems;
import dev.luizloyola.anima.core.brain.task.Task;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.core.store.Depot;
import dev.luizloyola.anima.core.store.Store;
import java.util.List;
import java.util.Optional;

/**
 * Take a gather's load home — whatever was got, if the fetch ran out short of its count. A
 * hunter who killed the last cow of a herd brings the meat home rather than carrying it about
 * while it looks for more. With nothing got there is no way, and the errand fails as it did before
 * this existed, without a walk home.
 *
 * <p><b>With no store at HOME the load stays in the pack</b>, carried to the base's spot: the
 * base's own set-up makes the chest. Grown by the delivery, the chest was crafted from the load,
 * nothing was left to put and the trip failed (2026-10-21).
 */
public final class BringBack implements CompoundTask {

    private final ItemSpec spec;
    private final int count;
    private final List<Method> methods;

    public BringBack(ItemSpec spec, int count) {
        this.spec = spec;
        this.count = count;
        // Appended, never inserted: a saved plan resumes its method by index.
        this.methods = List.of(new Deliver(), new KeepAtHome());
    }

    @Override
    public List<Method> methods() {
        return methods;
    }

    @Override
    public String describe() {
        return "bring the " + spec.name() + " home";
    }

    public ItemSpec spec() {
        return spec;
    }

    public int count() {
        return count;
    }

    /** Whether HOME has no store of the party's this body knows of: nowhere to put the load yet. */
    static boolean noStoreAtHome(BrainContext ctx) {
        Optional<Depot.Site> site = ctx.depot();
        return site.isPresent() && Store.ours(ctx).stream().noneMatch(store -> site.get().holds(store.anchor()));
    }

    private boolean holdsSome(BrainContext ctx) {
        return ctx.percepts().inventory().count(spec.matcher()) > 0;
    }

    private final class Deliver implements Method {
        @Override
        public boolean applicable(BrainContext ctx) {
            return holdsSome(ctx) && !noStoreAtHome(ctx);
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
            return List.of(new EnsureStore(), PutItems.deposit(spec, count));
        }

        @Override
        public String describe() {
            return "carry it home";
        }
    }

    private final class KeepAtHome implements Method {
        @Override
        public boolean applicable(BrainContext ctx) {
            return holdsSome(ctx) && noStoreAtHome(ctx);
        }

        @Override
        public double estimateCost(BrainContext ctx) {
            return 0.0;
        }

        @Override
        public List<Task> decompose(BrainContext ctx) {
            Depot.Site site = ctx.depot().orElseThrow();
            if (site.holds(ctx.percepts().position())) {
                return List.of();
            }
            Pos stand = EnsureTable.WalkToKnown.standableBeside(site.hint(), ctx);
            return List.of(new GoTo(stand.x(), stand.y(), stand.z()));
        }

        @Override
        public String describe() {
            return "no store yet: home with it in the pack";
        }
    }
}
