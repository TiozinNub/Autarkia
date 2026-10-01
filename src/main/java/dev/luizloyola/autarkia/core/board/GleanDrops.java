package dev.luizloyola.autarkia.core.board;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.board.WorkItem;
import dev.luizloyola.anima.core.brain.history.Deed;
import dev.luizloyola.anima.core.brain.sense.Drop;
import dev.luizloyola.anima.core.brain.task.GatherNearbyDrops;
import dev.luizloyola.anima.core.brain.task.Task;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.core.log.Category;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Pick up what this settler's own work let fall — saplings round a felled tree, a block that popped
 * off, the loot of whatever it killed, a player included (Luiz, 2026-09-30). Only near its own
 * work ({@link dev.luizloyola.anima.core.brain.history.WorkSpots}): other people's things lying about
 * are not its to take.
 *
 * <p>A condition, like {@link StowSurplus}: posted while such drops lie in sight, withdrawn when
 * they are gone some other way, never finished.
 */
public final class GleanDrops implements PersonalProject {

    /** Ticks between looks: a drop lies five minutes, and a look is a pass over what is in sight. */
    public static final int CHECK_INTERVAL = 40;

    /** Ticks a failed sweep sits out — the drops were out of reach, and will still be. */
    public static final int FAIL_COOLDOWN = 400;

    /**
     * Above work posted at 0.5, so a settler sweeps up after each tree rather than once the grove
     * is done and the saplings have gone; below {@code mind.preempt} (0.6), so it never cuts a tree
     * it is on.
     */
    public static final double PRIORITY = 0.55;

    private @Nullable WorkItem open;
    private boolean claimed;
    private int cooldown;
    private int clock;

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
        if (++clock % CHECK_INTERVAL != 0) {
            return;
        }
        boolean lying = lying(ctx);
        if (open == null && cooldown <= 0 && lying) {
            open = new GleanItem();
            ctx.journal().record(Category.PROJECT, open.describe(), "posted");
        } else if (open != null && !claimed && !lying) {
            ctx.journal().record(Category.PROJECT, open.describe(), "withdrawn (nothing lying)");
            open = null;
        }
    }

    private static boolean lying(BrainContext ctx) {
        for (Drop drop : ctx.percepts().drops()) {
            if (GatherNearbyDrops.wanted(drop, ItemSpec.ANYTHING, true, ctx)) {
                return true;
            }
        }
        return false;
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
        return "pick up what my work dropped" + (cooldown > 0 ? " (retry cooldown " + cooldown + "t)" : "");
    }

    private void clear() {
        open = null;
        claimed = false;
    }

    private static final class GleanItem implements WorkItem {
        @Override
        public double priority() {
            return PRIORITY;
        }

        @Override
        public Task root() {
            return new GatherNearbyDrops(ItemSpec.ANYTHING, true);
        }

        @Override
        public Deed doing() {
            return Deed.of(WorkDoings.GLEANING);
        }

        @Override
        public String describe() {
            return "pick up what my work dropped";
        }
    }
}
