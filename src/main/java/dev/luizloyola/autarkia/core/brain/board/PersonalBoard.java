package dev.luizloyola.autarkia.core.brain.board;

import dev.luizloyola.autarkia.core.brain.BrainContext;
import dev.luizloyola.autarkia.core.brain.task.ObtainItem;
import dev.luizloyola.autarkia.core.brain.task.Task;
import dev.luizloyola.autarkia.core.inv.ItemSpec;
import dev.luizloyola.autarkia.core.log.Category;
import java.util.Optional;

/**
 * Layer 3's first, degenerate incarnation: a personal board with one hardcoded stock
 * rule — keep {@code target} of {@code spec} in the pack (decision: Luiz — universal, visible, a
 * natural stress test). Real demand is DERIVED, a project computing a bill of materials and
 * posting this same item shape; the first real project retires this generator. Pure core.
 *
 * <p>Re-evaluated every {@link #CHECK_INTERVAL} ticks, offset per person so a settlement does not
 * think in lockstep. Posts when short and idle, withdraws an unclaimed item gone moot, and paces a
 * failure with {@link #FAIL_COOLDOWN} — five times an instinct's, and the pause is not dead time:
 * wandering fills the knowledge the retry needs.
 */
public final class PersonalBoard implements WorkSource {
    /** Ticks between board re-evaluations. Tuning knob. */
    public static final int CHECK_INTERVAL = 100;
    /** Ticks a failed item sits out before re-posting. Tuning knob. */
    public static final int FAIL_COOLDOWN = 600;

    private final ItemSpec spec;
    private final int target;
    private final double priority;
    private final int offset;

    private WorkItem open;
    private boolean claimed;
    private int cooldown;
    private int clock;
    /** Cadence beats seen — beat one is a warm-up: without it a newborn's first claim fires into
     *  an empty knowledge store and burns a 600t cooldown on nothing. */
    private int beats;

    public PersonalBoard(ItemSpec spec, int target, double priority, int offset) {
        this.spec = spec;
        this.target = target;
        this.priority = priority;
        this.offset = offset;
    }

    /** One board tick — cheap except on its cadence beats. */
    public void tick(BrainContext ctx) {
        if (cooldown > 0) {
            cooldown--;
        }
        if ((++clock + offset) % CHECK_INTERVAL != 0) {
            return;
        }
        if (++beats == 1) {
            return; // the warm-up beat: perception gets a full cadence before demand exists
        }
        boolean stocked = ctx.percepts().inventory().count(spec.matcher()) >= target;
        if (open == null && cooldown <= 0 && !stocked) {
            open = new StockItem();
            ctx.journal().record(Category.PROJECT, open.describe(), "posted");
        } else if (open != null && !claimed && stocked) {
            // The want evaporated before anyone worked it (a lucky scavenge, a dev give).
            ctx.journal().record(Category.PROJECT, open.describe(), "withdrawn (already stocked)");
            open = null;
        }
    }

    /** The board's line for the debug command: what is posted, claimed, cooling, or quiet. */
    public String describe(BrainContext ctx) {
        if (open != null) {
            return open.describe() + (claimed ? " — claimed" : " — posted")
                    + ", " + open.progress(ctx);
        }
        if (cooldown > 0) {
            return "idle (retry cooldown " + cooldown + "t)";
        }
        return "idle";
    }

    @Override
    public Optional<WorkItem> bestAvailable(BrainContext ctx) {
        return open != null && !claimed ? Optional.of(open) : Optional.empty();
    }

    @Override
    public void claimed(WorkItem item, BrainContext ctx) {
        claimed = true;
    }

    @Override
    public void completed(WorkItem item, BrainContext ctx) {
        ctx.journal().record(Category.PROJECT, item.describe(), "closed (" + item.progress(ctx) + ")");
        open = null;
        claimed = false;
    }

    @Override
    public void failed(WorkItem item, BrainContext ctx) {
        ctx.journal().record(Category.PROJECT, item.describe(),
                "unclaimed, retry cooldown (" + FAIL_COOLDOWN + "t)");
        open = null;
        claimed = false;
        cooldown = FAIL_COOLDOWN;
    }

    /** The one item this placeholder generator knows how to post. */
    private final class StockItem implements WorkItem {
        @Override
        public double priority() {
            return priority;
        }

        @Override
        public Task root() {
            return new ObtainItem(spec, target);
        }

        @Override
        public String describe() {
            return "acquire " + spec.name() + " x" + target;
        }

        @Override
        public String progress(BrainContext ctx) {
            return ctx.percepts().inventory().count(spec.matcher()) + "/" + target + " held";
        }
    }
}
