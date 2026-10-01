package dev.luizloyola.autarkia.core.board;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.board.WorkItem;
import dev.luizloyola.anima.core.brain.history.Deed;
import dev.luizloyola.anima.core.brain.task.Task;
import dev.luizloyola.anima.core.brain.task.TidyPack;
import dev.luizloyola.anima.core.inv.Inventory;
import dev.luizloyola.anima.core.inv.Tidy;
import dev.luizloyola.anima.core.log.Category;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Sort the pack when it has drifted from the body's layout — tools back on the hotbar, clutter off
 * it (Luiz, 2026-10-01; docs/superpowers/specs/2026-10-01-tool-up-and-tidy-pack-design.md). Most
 * pickups land in place already; this is for what a draw or a full hotbar left behind.
 *
 * <p>{@link StowSurplus}'s shape, and like it lands between tasks by construction: work never
 * preempts mid-flight.
 */
public final class TidyUp implements StandingProject {

    public static final int CHECK_INTERVAL = 100;
    public static final int FAIL_COOLDOWN = 600;

    /** Just above the stow, so a pack is sorted before it is carried home. */
    public static final double PRIORITY = 0.27;

    /**
     * What a tidy must gain to be worth an errand: a stack of clutter on the hotbar, or a sword a
     * long way from its slot. Single moves below this wait for company.
     */
    public static final double WORTH = 5.0;

    private @Nullable WorkItem open;
    private boolean claimed;
    private int cooldown;
    private int clock;
    private int beats;

    @Override
    public double priority() {
        return PRIORITY;
    }

    @Override
    public List<WorkItem> open() {
        return open == null ? List.of() : List.of(open);
    }

    @Override
    public boolean finished() {
        return false;
    }

    @Override
    public void tick(BrainContext ctx) {
        if (cooldown > 0) {
            cooldown--;
        }
        if (++clock % CHECK_INTERVAL != 0 || ++beats == 1) {
            return;
        }
        boolean untidy = gain(ctx.percepts().inventory()) >= WORTH;
        if (open == null && cooldown <= 0 && untidy) {
            open = new TidyItem();
            ctx.journal().record(Category.PROJECT, open.describe(), "posted");
        } else if (open != null && !claimed && !untidy) {
            ctx.journal().record(Category.PROJECT, open.describe(), "withdrawn (tidy)");
            open = null;
        }
    }

    private static double gain(Inventory pack) {
        double total = 0.0;
        for (Tidy.Swap swap : Tidy.plan(pack, pack.layout(), TidyPack.MAX_MOVES)) {
            total += swap.gain();
        }
        return total;
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

    @Override
    public void lapsed(WorkItem item) {
        claimed = false;
    }

    @Override
    public String describe() {
        return "keep the pack tidy" + (cooldown > 0 ? " (retry cooldown " + cooldown + "t)" : "");
    }

    private void clear() {
        open = null;
        claimed = false;
    }

    private static final class TidyItem implements WorkItem {
        @Override
        public double priority() {
            return PRIORITY;
        }

        @Override
        public Task root() {
            return new TidyPack();
        }

        @Override
        public Deed doing() {
            return Deed.of(WorkDoings.TIDYING);
        }

        @Override
        public String describe() {
            return "tidy the pack";
        }

        @Override
        public String progress(BrainContext ctx) {
            return Tidy.plan(ctx.percepts().inventory(), ctx.percepts().inventory().layout(),
                    TidyPack.MAX_MOVES).size() + " moves left";
        }
    }

    // ── continuity ───────────────────────────────────────────────────────────────────────────

    @Override
    public KeepStocked.State snapshot() {
        return new KeepStocked.State(cooldown, clock, beats, open != null, claimed);
    }

    @Override
    public void restore(KeepStocked.State state) {
        this.cooldown = state.cooldown();
        this.clock = state.clock();
        this.beats = state.beats();
        this.open = state.wanting() ? new TidyItem() : null;
        this.claimed = state.claimed();
    }

    @Override
    public @Nullable WorkItem openItem() {
        return open;
    }
}
