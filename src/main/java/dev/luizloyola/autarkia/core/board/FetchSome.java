package dev.luizloyola.autarkia.core.board;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.task.CompoundTask;
import dev.luizloyola.anima.core.brain.task.Method;
import dev.luizloyola.anima.core.brain.task.ObtainItem;
import dev.luizloyola.anima.core.brain.task.Task;
import dev.luizloyola.anima.core.inv.ItemSpec;
import java.util.List;
import java.util.Set;

/**
 * A gather's fetch: get this much, settling for what is held if the fetch runs out. With nothing
 * held it fails as the fetch did, which a {@code Try} would hide. A trip priced out of every tree
 * then fails priced out, and its gather earns budget (Emily, 2026-10-02, {@code run/normal}).
 */
public final class FetchSome implements CompoundTask {

    private final ItemSpec spec;
    private final int count;
    private final Set<String> pursued;
    private final List<Method> methods;

    public FetchSome(ItemSpec spec, int count) {
        this(spec, count, Set.of());
    }

    /** @param pursued what it is fetched for, as {@link ObtainItem}'s own — an expedition's crafts */
    public FetchSome(ItemSpec spec, int count, Set<String> pursued) {
        this.spec = spec;
        this.count = count;
        this.pursued = Set.copyOf(pursued);
        this.methods = List.of(new Fetch(), new Settle());
    }

    @Override
    public List<Method> methods() {
        return methods;
    }

    @Override
    public String describe() {
        return "fetch up to " + count + " " + spec.name();
    }

    public ItemSpec spec() {
        return spec;
    }

    public int count() {
        return count;
    }

    public Set<String> pursued() {
        return pursued;
    }

    /** First on the tie, so a body already carrying some still goes for the rest. */
    private final class Fetch implements Method {
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
            return List.of(new ObtainItem(spec, count, pursued, ObtainItem.Sources.NOT_STORES));
        }

        @Override
        public String describe() {
            return "go and get it";
        }
    }

    private final class Settle implements Method {
        @Override
        public boolean applicable(BrainContext ctx) {
            return ctx.percepts().inventory().count(spec.matcher()) > 0;
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
            return "make do with what is held";
        }
    }
}
