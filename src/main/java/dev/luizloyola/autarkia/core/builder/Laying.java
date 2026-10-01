package dev.luizloyola.autarkia.core.builder;

import dev.luizloyola.anima.core.brain.act.Placing;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.autarkia.core.bp.Planner;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A {@link Step} in the world, as a body places it: the block turned as the building faces, the
 * stand the proved order placed it from, and the items it takes.
 *
 * @param also  the step's other cells — a door's upper half, a bed's head
 * @param count items placed: two for a double slab, the candles in a cell
 * @param uses  what the clicks after it hold: a pot's plant, a cauldron's bucket, a path's shovel
 */
public record Laying(Section section, Placing placing, List<Pos> also, Pos stand, int count, List<Use> uses) {

    /**
     * Items a click holds, any one of them.
     *
     * @param consumed whether the click uses one up — a plant, a bucket — or only uses it, as a tool
     */
    public record Use(Set<String> items, boolean consumed) {
        public Use {
            items = Set.copyOf(items);
        }
    }

    public Laying {
        also = List.copyOf(also);
        uses = List.copyOf(uses);
    }

    public Laying(Section section, Placing placing, List<Pos> also, Pos stand, int count) {
        this(section, placing, also, stand, count, List.of());
    }

    public Pos cell() {
        return placing.cell();
    }

    /** What the cell holds once the step is done. */
    public String block() {
        return placing.block().isEmpty() ? placing.itemId() : placing.block();
    }

    /**
     * Whether a cell holding {@code id} in {@code state} is this step done: its block, and whatever
     * it counts as many as the plan asks — a slab made double, the third candle — never only the first.
     */
    public boolean standsAs(String id, Map<String, String> state) {
        if (!block().equals(id)) {
            return false;
        }
        for (Map.Entry<String, String> wanted : placing.state().entrySet()) {
            boolean counted = Planner.COUNTS.contains(wanted.getKey())
                    || wanted.getKey().equals("type") && wanted.getValue().equals("double");
            if (counted && !wanted.getValue().equals(state.get(wanted.getKey()))) {
                return false;
            }
        }
        return true;
    }
}
