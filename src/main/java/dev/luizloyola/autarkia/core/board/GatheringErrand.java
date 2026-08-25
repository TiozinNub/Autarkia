package dev.luizloyola.autarkia.core.board;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.CompoundTask;
import dev.luizloyola.anima.core.brain.task.EnsureStore;
import dev.luizloyola.anima.core.brain.task.Method;
import dev.luizloyola.anima.core.brain.task.ObtainItem;
import dev.luizloyola.anima.core.brain.task.PutItems;
import dev.luizloyola.anima.core.brain.task.Task;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.core.store.Store;
import java.util.List;

/**
 * Go and get this much, then put it in the yard — one member's whole trip for a {@link Gather}.
 *
 * <p><b>The fetch may not source from a store</b> ({@link ObtainItem.Sources#NOT_STORES}). A gather
 * measures its remainder against what the yard already holds, so an errand allowed to take from
 * storage would be sent to fetch the very goods it is counting: wanting 64 with 32 already banked,
 * it would make a new 32 by emptying the yard.
 *
 * <p><b>The deposit resolves its chest on arrival.</b> Nobody knows the anchor when the trip is
 * minted — the yard is a hint and {@link EnsureStore} may still have to grow a chest on it — which
 * is why this is {@link PutItems#deposit} rather than {@code PutItems.of}, and why the deposit is a
 * full one rather than {@code PutAwaySurplus}: a gather's load is cargo, and cargo is exactly what
 * the project's {@code reserved()} has told the stow machinery to leave alone.
 */
public final class GatheringErrand implements CompoundTask {

    private final ItemSpec spec;
    private final int count;
    private final Pos yard;
    private final List<Method> methods;

    public GatheringErrand(ItemSpec spec, int count, Pos yard) {
        this.spec = spec;
        this.count = count;
        this.yard = yard;
        this.methods = List.of(new FetchThenDeposit());
    }

    @Override
    public List<Method> methods() {
        return methods;
    }

    @Override
    public String describe() {
        return "fetch " + count + " " + spec.name() + " for the yard";
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

    private final class FetchThenDeposit implements Method {
        @Override
        public boolean applicable(BrainContext ctx) {
            // ObtainItem's own methods decide whether that means picking up, producing or crafting,
            // and EnsureStore's decide walking or building. Neither can be out of ways here.
            return true;
        }

        @Override
        public double estimateCost(BrainContext ctx) {
            // Priced at the yard, like PutAwaySurplus with a hint: the fetch prices itself per
            // asker inside ObtainItem, and this errand is already claimed by the time it is asked.
            return Store.distance(yard, ctx.percepts().position());
        }

        @Override
        public List<Task> decompose(BrainContext ctx) {
            return List.of(new ObtainItem(spec, count, java.util.Set.of(),
                            ObtainItem.Sources.NOT_STORES),
                    new EnsureStore(yard),
                    PutItems.deposit(spec, count));
        }

        @Override
        public String describe() {
            return "fetch it, then put it in the yard";
        }
    }
}
