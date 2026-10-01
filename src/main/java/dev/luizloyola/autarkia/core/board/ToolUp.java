package dev.luizloyola.autarkia.core.board;

import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.board.WorkItem;
import dev.luizloyola.anima.core.brain.history.Deed;
import dev.luizloyola.anima.core.brain.task.Task;
import dev.luizloyola.anima.core.inv.Inventory;
import dev.luizloyola.anima.core.inv.ItemCall;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.core.inv.ItemStack;
import dev.luizloyola.anima.core.log.Category;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Keep a sound tool of one family at the age's tier, and make one when there is none — the first
 * personal project that goes shopping for a tool nobody asked for (Luiz, 2026-10-01, reversing the
 * 2026-08-15/20 ruling for these four families only;
 * docs/superpowers/specs/2026-10-01-tool-up-and-tidy-pack-design.md).
 *
 * <p>Upgrades at once: an age reached leaves the old tool short of the tier and the new one is made.
 * The old one is kept and used up first ({@code ToolChoice}), so this reserves every lower tier as
 * well as the current one.
 *
 * <p>Shaped after {@link KeepStocked}: a beat every {@link #CHECK_INTERVAL} ticks after a warm-up,
 * withdrawn only while unclaimed, and a failure sits out {@link #FAIL_COOLDOWN}.
 */
public final class ToolUp implements StandingProject {

    public static final int CHECK_INTERVAL = 100;
    public static final int FAIL_COOLDOWN = 600;

    /**
     * No tool of the tier at all — missing, or an age just reached. Above upkeep (0.4) and a side
     * node (0.45), under a core node (0.5) and a station going down (0.52).
     */
    public static final double LACKING = 0.46;

    /** A worn tool still works: above the stow (0.25), under upkeep. */
    public static final double SPARE = 0.35;

    /** Lower tiers kept to be used up — more than a pack ever holds of one family. */
    static final int OLD_KEPT = 9;

    private final Tools.Family family;

    private @Nullable WorkItem open;
    private boolean claimed;
    private int cooldown;
    private int clock;
    private int beats;
    private double priority = LACKING;
    /** Read with the owner's eyes on a beat, for {@link #reserved()}, which has none. */
    private int tier;
    private int worn;
    private boolean seen;

    public ToolUp(Tools.Family family) {
        this.family = family;
    }

    /** All four, in the hotbar's order. */
    public static List<ToolUp> settlerDefaults() {
        return java.util.Arrays.stream(Tools.Family.values()).map(ToolUp::new).toList();
    }

    @Override
    public double priority() {
        return priority;
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
        boolean beat = (++clock) % CHECK_INTERVAL == 0;
        if (!seen || beat) {
            read(ctx); // the first tick too, so a reload never reserves wooden-age kit for a beat
            seen = true;
        }
        if (!beat || ++beats == 1) {
            return; // the warm-up beat, KeepStocked's reason
        }
        double spareBelow = ctx.profile().d(ProfileAspect.HANDLING_SPARE_BELOW);
        Inventory pack = ctx.percepts().inventory();
        boolean covered = Tools.covered(family, tier, pack, spareBelow);
        if (open == null && cooldown <= 0 && !covered) {
            open = new ToolItem();
            ctx.journal().record(Category.PROJECT, open.describe(), "posted");
        } else if (open != null && !claimed && covered) {
            ctx.journal().record(Category.PROJECT, open.describe(), "withdrawn (tooled up)");
            open = null;
        }
        if (open != null && !claimed) {
            priority = hasTier(pack) ? SPARE : LACKING;
        }
    }

    private void read(BrainContext ctx) {
        tier = Tools.currentTier(family, ctx.gate());
        double spareBelow = ctx.profile().d(ProfileAspect.HANDLING_SPARE_BELOW);
        Inventory pack = ctx.percepts().inventory();
        worn = 0;
        for (int slot = 0; slot < Inventory.ARMOR_START; slot++) {
            ItemStack stack = pack.get(slot);
            if (Tools.tierOf(family, stack.id()) >= tier && Tools.worn(stack, spareBelow)) {
                worn++;
            }
        }
    }

    /** A tool at the tier or better, worn or not. */
    private boolean hasTier(Inventory pack) {
        for (int slot = 0; slot < Inventory.ARMOR_START; slot++) {
            if (Tools.tierOf(family, pack.get(slot).id()) >= tier) {
                return true;
            }
        }
        return false;
    }

    /**
     * One sound tool at the tier and each worn one beside it, then everything older. A second
     * sound one is cargo, as it always was. Golden counts as old: kept, never the one at the tier.
     */
    @Override
    public List<ItemCall> reserved() {
        Set<String> current = new HashSet<>();
        Set<String> old = new HashSet<>(Set.of(family.at("golden")));
        for (int t = 0; t < Tools.TIERS.size(); t++) {
            (t >= tier ? current : old).add(family.at(Tools.TIERS.get(t)));
        }
        return List.of(
                ItemCall.need(ItemSpec.anyOf(current), 1 + worn),
                ItemCall.want(ItemSpec.anyOf(old), OLD_KEPT));
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
        return "keep a " + family.tool() + (cooldown > 0 ? " (retry cooldown " + cooldown + "t)" : "");
    }

    private void clear() {
        open = null;
        claimed = false;
    }

    private final class ToolItem implements WorkItem {
        @Override
        public boolean buildsOnTheWay() {
            return true;
        }

        @Override
        public double priority() {
            return priority;
        }

        @Override
        public Task root() {
            return new KeepTool(family);
        }

        @Override
        public Deed doing() {
            return Deed.of(WorkDoings.STOCKING_UP, WorkDoings.goods(family.any()));
        }

        @Override
        public String describe() {
            return "make a " + Tools.TIERS.get(tier) + " " + family.tool();
        }

        @Override
        public String progress(BrainContext ctx) {
            return hasTier(ctx.percepts().inventory()) ? "worn" : "none at the tier";
        }
    }

    // ── continuity ───────────────────────────────────────────────────────────────────────────

    /** {@link KeepStocked}'s rhythm. The tier and the priority are re-read on the first tick. */
    @Override
    public KeepStocked.State snapshot() {
        return new KeepStocked.State(cooldown, clock, beats, open != null, claimed);
    }

    @Override
    public void restore(KeepStocked.State state) {
        this.cooldown = state.cooldown();
        this.clock = state.clock();
        this.beats = state.beats();
        this.open = state.wanting() ? new ToolItem() : null;
        this.claimed = state.claimed();
    }

    @Override
    public @Nullable WorkItem openItem() {
        return open;
    }
}
