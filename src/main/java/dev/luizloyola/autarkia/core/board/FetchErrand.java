package dev.luizloyola.autarkia.core.board;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.CompoundTask;
import dev.luizloyola.anima.core.brain.task.EnsureTable;
import dev.luizloyola.anima.core.brain.task.GoTo;
import dev.luizloyola.anima.core.brain.task.Method;
import dev.luizloyola.anima.core.brain.task.TakeItems;
import dev.luizloyola.anima.core.brain.task.Task;
import dev.luizloyola.anima.core.inv.Inventory;
import dev.luizloyola.anima.core.inv.ItemSpec;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Empty a far store and bring what was in it home (group-up spec, decision 21): walk beside it, take
 * everything, then carry home what the pack gained since setting out — never the rest of the pack,
 * whose tools are the body's own. What the store held is only known once there: a far chest is
 * rarely loaded at the merge. An empty store fails the take, and is let go all the same.
 */
public final class FetchErrand implements CompoundTask {

    /** More than a double chest holds, so one take empties it. */
    private static final int ALL = 54 * 64;

    private final Pos at;
    private final List<Method> methods = List.of(new Way());

    public FetchErrand(Pos at) {
        this.at = at;
    }

    @Override
    public List<Method> methods() {
        return methods;
    }

    @Override
    public String describe() {
        return "fetch what was left at (" + at.x() + ", " + at.y() + ", " + at.z() + ")";
    }

    public Pos at() {
        return at;
    }

    static Map<String, Integer> counts(Inventory inventory) {
        Map<String, Integer> counts = new HashMap<>();
        for (Inventory.Entry entry : inventory.occupied()) {
            counts.merge(entry.stack().id(), entry.stack().count(), Integer::sum);
        }
        return counts;
    }

    /** What {@code after} holds more of than {@code before}, by id. */
    static Map<String, Integer> gained(Map<String, Integer> before, Map<String, Integer> after) {
        Map<String, Integer> gained = new HashMap<>();
        after.forEach((id, count) -> {
            int more = count - before.getOrDefault(id, 0);
            if (more > 0) {
                gained.put(id, more);
            }
        });
        return gained;
    }

    private final class Way implements Method {
        @Override
        public boolean applicable(BrainContext ctx) {
            return EnsureTable.WalkToKnown.standBeside(at, ctx).isPresent();
        }

        @Override
        public double estimateCost(BrainContext ctx) {
            return 0.0;
        }

        @Override
        public List<Task> decompose(BrainContext ctx) {
            Pos beside = EnsureTable.WalkToKnown.standBeside(at, ctx).orElseThrow();
            return List.of(new GoTo(beside.x(), beside.y(), beside.z()), new TakeItems(at, ItemSpec.ANYTHING, ALL),
                    new BringGained(counts(ctx.percepts().inventory())));
        }

        @Override
        public String describe() {
            return "walk there, empty it, bring it home";
        }
    }

    /** Home with whatever the pack holds more of than {@code before}; decomposed only once the take is done. */
    public static final class BringGained implements CompoundTask {

        private final Map<String, Integer> before;
        private final List<Method> methods = List.of(new Carry());

        public BringGained(Map<String, Integer> before) {
            this.before = Map.copyOf(before);
        }

        public Map<String, Integer> before() {
            return before;
        }

        @Override
        public List<Method> methods() {
            return methods;
        }

        @Override
        public String describe() {
            return "bring what came out of it home";
        }

        private final class Carry implements Method {
            @Override
            public boolean applicable(BrainContext ctx) {
                return !gained(before, counts(ctx.percepts().inventory())).isEmpty();
            }

            @Override
            public double estimateCost(BrainContext ctx) {
                return 0.0;
            }

            @Override
            public List<Task> decompose(BrainContext ctx) {
                Map<String, Integer> got = gained(before, counts(ctx.percepts().inventory()));
                return List.of(new BringBack(ItemSpec.anyOf(got.keySet()),
                        got.values().stream().mapToInt(Integer::intValue).sum()));
            }

            @Override
            public String describe() {
                return "carry it home";
            }
        }
    }
}
