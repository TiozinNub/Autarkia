package dev.luizloyola.autarkia.core.brain.task;

import dev.luizloyola.autarkia.core.brain.BrainContext;
import dev.luizloyola.autarkia.core.inv.ItemSpec;
import java.util.ArrayList;
import java.util.List;

/**
 * Have {@code n} of {@code spec} in the pack — the first {@link AchieveTask}. The satisfied-check
 * Is the goal (no "already stocked" method needed); each executor round picks the cheapest way to
 * make progress: scavenge matching drops in sight ({@link PickUpNearby}) or fell a remembered grove
 * ({@link ChopKnownTree}, offered only for wood — the one thing chopping produces).
 *
 * <p>Always a SUBGOAL in practice: a craft or build project computes a bill of materials and posts
 * requirements; nobody wants logs as an end state.
 */
public final class ObtainItem implements AchieveTask {
    private final ItemSpec spec;
    private final int count;
    private final List<Method> methods;

    public ObtainItem(ItemSpec spec, int count) {
        this.spec = spec;
        this.count = count;
        List<Method> ways = new ArrayList<>();
        ways.add(new PickUpNearby(spec));
        if (spec == ItemSpec.LOGS) {
            // Chopping produces exactly one thing.
            ways.add(new ChopKnownTree());
        }
        this.methods = List.copyOf(ways);
    }

    @Override
    public boolean satisfied(BrainContext ctx) {
        return ctx.percepts().inventory().count(spec.matcher()) >= count;
    }

    /** The stocked count: rounds that grow the pile never count against the rounds cap. */
    @Override
    public double progress(BrainContext ctx) {
        return ctx.percepts().inventory().count(spec.matcher());
    }

    @Override
    public List<Method> methods() {
        return methods;
    }

    @Override
    public String describe() {
        return "obtain " + spec.name() + " x" + count;
    }
}
