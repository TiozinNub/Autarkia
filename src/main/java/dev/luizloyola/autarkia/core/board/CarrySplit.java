package dev.luizloyola.autarkia.core.board;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.inv.Inventory;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.core.inv.ItemStack;

/**
 * One claim is what this body can carry, capped — the only {@link Split} in v1.
 *
 * <p>{@code trip = min(remainder, min(MAX_TRIP, room in the pack))}. There is no share and no
 * divisor: nobody is handed work, so there is never a count of idle members to divide against.
 * A body good enough to carry the whole job takes the whole job.
 */
public final class CarrySplit implements Split {

    /** The one instance — registered at bootstrap and meant by the store and the project. */
    public static final CarrySplit INSTANCE = new CarrySplit();

    /** The smallest payload worth a walk, and so also the slate's slice size — see {@code Gather}. */
    public static final int MIN_TRIP = 16;

    /** The most worth carrying before putting it down — one stack. */
    public static final int MAX_TRIP = 64;

    private CarrySplit() {
    }

    @Override
    public String id() {
        return "carry";
    }

    @Override
    public int tripFor(int remainder, ItemSpec spec, BrainContext asker) {
        if (remainder <= 0) {
            return 0;
        }
        return Math.min(remainder, roomFor(spec, asker.percepts().inventory()));
    }

    /**
     * How much more of what the project wants would fit in the pack, stopping once the answer can
     * no longer matter.
     *
     * <p><b>An empty slot is counted as {@link #MAX_TRIP}</b>, not as the item's real stack size,
     * which nothing in {@code core} can ask for an item the body is not already holding. The
     * over-estimate is bounded by one stack and only bites a spec that stacks below 64; the stow
     * machinery already handles a body that comes back fuller than it meant to.
     */
    private static int roomFor(ItemSpec spec, Inventory pack) {
        int room = 0;
        for (int slot = Inventory.HOTBAR_START; slot < Inventory.ARMOR_START; slot++) {
            ItemStack stack = pack.get(slot);
            if (stack.isEmpty()) {
                room += MAX_TRIP;
            } else if (spec.matches(stack.id())) {
                room += stack.remainingSpace();
            }
            if (room >= MAX_TRIP) {
                return MAX_TRIP;
            }
        }
        return room;
    }
}
