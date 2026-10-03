package dev.luizloyola.autarkia.core.board;

import dev.luizloyola.anima.core.brain.BrainContext;
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
 * Go and get this much, then put it in a home chest — one member's whole trip for a {@link Gather}.
 *
 * <p><b>The fetch may not source from a store</b> ({@link ObtainItem.Sources#NOT_STORES}). A gather
 * measures its remainder against what HOME already holds, so an errand allowed to take from
 * storage would be sent to fetch the very goods it is counting: wanting 64 with 32 already banked,
 * it would make a new 32 by emptying a home chest.
 *
 * <p><b>A fetch that runs out still delivers</b> what it got ({@link BringBack}): the load is the
 * party's, and a trip is over when there is no more to be had, not only when the count is met.
 *
 * <p><b>The deposit resolves its chest on arrival.</b> Nobody knows the anchor when the trip is
 * minted — HOME may have no chest yet and {@link EnsureStore} may still have to grow one — which
 * is why this is {@link PutItems#deposit} rather than {@code PutItems.of}, and why the deposit is a
 * full one rather than {@code PutAwaySurplus}: a gather's load is cargo, and cargo is exactly what
 * the project's {@code reserved()} has told the stow machinery to leave alone.
 */
public final class GatheringErrand implements CompoundTask {

    private final ItemSpec spec;
    private final int count;
    private final java.util.Set<String> pursued;
    private final List<Method> methods;

    public GatheringErrand(ItemSpec spec, int count) {
        this(spec, count, java.util.Set.of());
    }

    /** @param pursued what it is fetched for — see {@link FetchSome} */
    public GatheringErrand(ItemSpec spec, int count, java.util.Set<String> pursued) {
        this.spec = spec;
        this.count = count;
        this.pursued = java.util.Set.copyOf(pursued);
        this.methods = List.of(new FetchThenDeposit());
    }

    @Override
    public List<Method> methods() {
        return methods;
    }

    @Override
    public String describe() {
        return "fetch " + count + " " + spec.name() + " for home";
    }

    public ItemSpec spec() {
        return spec;
    }

    public int count() {
        return count;
    }

    public java.util.Set<String> pursued() {
        return pursued;
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
            // Priced by the walk home: the fetch prices itself per asker inside ObtainItem, and
            // this errand is already claimed by the time it is asked.
            return ctx.depot().map(site -> Store.distance(site.hint(), ctx.percepts().position()))
                    .orElse(0.0);
        }

        @Override
        public List<Task> decompose(BrainContext ctx) {
            return List.of(new FetchSome(spec, count, pursued), new BringBack(spec, count));
        }

        @Override
        public String describe() {
            return "fetch it, then take it home";
        }
    }
}
