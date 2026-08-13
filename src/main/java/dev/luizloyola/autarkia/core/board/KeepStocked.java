package dev.luizloyola.autarkia.core.board;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.board.WorkItem;
import dev.luizloyola.anima.core.brain.task.ObtainItem;
import dev.luizloyola.anima.core.brain.task.Task;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.core.inv.Kit;
import dev.luizloyola.anima.core.log.Category;
import java.util.List;

/**
 * Keep {@code target} of {@code spec} in the pack — the standing want every fresh settler has
 * ("keep 16 logs"; decision: Luiz).
 *
 * <p>A condition, not an outcome: {@link #finished()} is always {@code false}, and reaching the
 * target only stops posting until the pile shrinks — hence the withdraw path.
 *
 * <p>Re-evaluated every {@link #CHECK_INTERVAL} ticks, offset per agent so a settlement does not
 * think in lockstep. The first beat is skipped as a warm-up: a newborn's first claim would
 * otherwise fire into an empty knowledge store and burn the cooldown on nothing. A failure waits
 * {@link #FAIL_COOLDOWN} — not dead time, since wandering fills knowledge and is why the retry
 * succeeds.
 */
public final class KeepStocked implements PersonalProject {

    /** Ticks between re-evaluations. */
    public static final int CHECK_INTERVAL = 100;
    /** Ticks a failed item sits out before re-posting. Tuning knob. */
    public static final int FAIL_COOLDOWN = 600;

    private final ItemSpec spec;
    private final int target;
    private final double priority;
    private final int offset;
    /** What working the posted item calls for — content wired in by whoever posts the project. */
    private final Kit kit;

    /** The one item this project posts at a time, or null when it wants nothing right now. */
    private WorkItem open;
    /** Whether somebody is out there working {@link #open} — see {@link #claimed}. */
    private boolean claimed;
    private int cooldown;
    private int clock;
    /** Cadence beats seen — beat one is the warm-up skip. */
    private int beats;

    public KeepStocked(ItemSpec spec, int target, double priority, int offset) {
        this(spec, target, priority, offset, Kit.NONE);
    }

    public KeepStocked(ItemSpec spec, int target, double priority, int offset, Kit kit) {
        this.spec = spec;
        this.target = target;
        this.priority = priority;
        this.offset = offset;
        this.kit = kit;
    }

    @Override
    public double priority() {
        return priority;
    }

    @Override
    public List<WorkItem> open() {
        return open == null ? List.of() : List.of(open);
    }

    /** A standing condition is never done with — see the class note. */
    @Override
    public boolean finished() {
        return false;
    }

    /** Cheap except on its cadence beats. */
    @Override
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
        boolean stocked = stocked(ctx);
        if (open == null && cooldown <= 0 && !stocked) {
            open = new StockItem();
            ctx.journal().record(Category.PROJECT, open.describe(), "posted");
        } else if (open != null && !claimed && stocked) {
            // Only while UNCLAIMED: an errand somebody is already walking to is theirs to
            // finish — it satisfies itself the moment the pack is full.
            ctx.journal().record(Category.PROJECT, open.describe(), "withdrawn (already stocked)");
            open = null;
        }
    }

    @Override
    public void claimed(WorkItem item) {
        claimed = true;
    }

    @Override
    public void completed(WorkItem item, BrainContext ctx) {
        clear();
    }

    @Override
    public void failed(WorkItem item, BrainContext ctx) {
        ctx.journal().record(Category.PROJECT, item.describe(),
                "unclaimed, retry cooldown (" + FAIL_COOLDOWN + "t)");
        clear();
        cooldown = FAIL_COOLDOWN;
    }

    /**
     * A lapsed lease is not a failure: the worker was pulled away, which says nothing about
     * whether the errand is doable. Back on offer without the retry cooldown.
     */
    @Override
    public void lapsed(WorkItem item) {
        claimed = false;
    }

    @Override
    public String describe() {
        return "keep " + spec.name() + " x" + target
                + (cooldown > 0 ? " (retry cooldown " + cooldown + "t)" : "");
    }

    private boolean stocked(BrainContext ctx) {
        return ctx.percepts().inventory().count(spec.matcher()) >= target;
    }

    private void clear() {
        open = null;
        claimed = false;
    }

    /** The one item shape this project mints. */
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
        public Kit kit() {
            return kit;
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

    // ── continuity ───────────────────────────────────────────────────────────────────────────

    /**
     * A standing want's rhythm and whether an errand is out — everything that outlives a tick.
     * The spec, target and priority are not here: constants rebuilt by the field initializer.
     *
     * <p>{@code wanting} rather than the item itself: only this project mints one, and on a
     * single-member board there is nobody to tell two claimants apart, so a boolean is a complete
     * description and the project re-mints on restore. A SHARED board will need durable work-item
     * identity.
     */
    public record State(int cooldown, int clock, int beats, boolean wanting, boolean claimed) {
    }

    public State snapshot() {
        return new State(cooldown, clock, beats, open != null, claimed);
    }

    /** Puts the rhythm back, re-minting the open errand if one was out. */
    public void restore(State state) {
        this.cooldown = state.cooldown();
        this.clock = state.clock();
        this.beats = state.beats();
        this.open = state.wanting() ? new StockItem() : null;
        this.claimed = state.claimed();
    }

    /** The errand currently on offer, or null — so a reload can point an arbiter back at it. */
    public WorkItem openItem() {
        return open;
    }
}
