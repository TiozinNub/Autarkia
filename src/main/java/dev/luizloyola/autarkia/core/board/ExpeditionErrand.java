package dev.luizloyola.autarkia.core.board;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.sense.BeingId;
import dev.luizloyola.anima.core.brain.task.CompoundTask;
import dev.luizloyola.anima.core.brain.task.Idle;
import dev.luizloyola.anima.core.brain.task.Method;
import dev.luizloyola.anima.core.brain.task.Task;
import dev.luizloyola.anima.core.inv.ItemSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * One member's part in an expedition: a lead-in, then the gather trip. The one leading waits
 * {@code muster} ticks at HOME for others to take a share; a companion keeps with
 * {@code leader} until it knows the source itself ({@link TravelWith}).
 */
public final class ExpeditionErrand implements CompoundTask {

    private final ItemSpec spec;
    private final int count;
    private final Set<String> pursued;
    private final @Nullable BeingId leader;
    private final int muster;
    private final List<Method> methods = List.of(new LeadInThenFetch());

    public ExpeditionErrand(ItemSpec spec, int count, Set<String> pursued, @Nullable BeingId leader, int muster) {
        this.spec = spec;
        this.count = count;
        this.pursued = Set.copyOf(pursued);
        this.leader = leader;
        this.muster = Math.max(0, muster);
    }

    @Override
    public List<Method> methods() {
        return methods;
    }

    @Override
    public String describe() {
        return leader == null ? "lead the way for " + count + " " + spec.name()
                : "go along for " + count + " " + spec.name();
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

    public @Nullable BeingId leader() {
        return leader;
    }

    public int muster() {
        return muster;
    }

    private final class LeadInThenFetch implements Method {
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
            List<Task> steps = new ArrayList<>(2);
            if (leader != null) {
                steps.add(new TravelWith(leader, spec, pursued));
            } else if (muster > 0) {
                steps.add(new Idle(muster));
            }
            steps.add(new GatheringErrand(spec, count, pursued));
            return steps;
        }

        @Override
        public String describe() {
            return leader == null ? "wait for company, then go" : "keep with the leader, then fetch";
        }
    }
}
