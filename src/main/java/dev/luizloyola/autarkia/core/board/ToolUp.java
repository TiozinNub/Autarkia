package dev.luizloyola.autarkia.core.board;

import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.WorkToleranceCurve;
import dev.luizloyola.anima.core.brain.board.WorkItem;
import dev.luizloyola.anima.core.brain.history.Deed;
import dev.luizloyola.anima.core.brain.task.AtOneBench;
import dev.luizloyola.anima.core.brain.task.Task;
import dev.luizloyola.anima.core.inv.Inventory;
import dev.luizloyola.anima.core.inv.ItemCall;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.core.inv.ItemStack;
import dev.luizloyola.anima.core.log.Category;
import java.util.ArrayList;
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
 * withdrawn only while unclaimed, and a failure sits out {@link SetUp#cooldownAfter SetUp's wait},
 * doubled for each failure in a row. A tool priced out earns budget as a party errand does
 * ({@link GrowsBudget}): a lone settler whose only stone lay past an axe's budget re-posted it
 * every 600 ticks, 2,014 times (forest, 2026-10-02). Both reset when the tool is had or the age
 * names another.
 *
 * <p>One item per family, so a tool that cannot be made does not hold up the others; but the one
 * claimed makes every family posted beside it too, {@link AtOneBench at one bench}, so the table
 * goes down once for them all (in-world, 2026-10-01: three put-downs in 3 s).
 */
public final class ToolUp implements StandingProject, GrowsBudget {

    public static final int CHECK_INTERVAL = 100;

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
    /** The families posted together, this one among them — whose open items a run takes along. */
    private List<ToolUp> bench = List.of(this);

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
    /** Budget earned priced out, and failures in a row — both for the tool at {@link #tier}. */
    private int steps;
    private int failures;

    public ToolUp(Tools.Family family) {
        this.family = family;
    }

    /** All four, in the hotbar's order. */
    public static List<ToolUp> settlerDefaults() {
        List<ToolUp> all = java.util.Arrays.stream(Tools.Family.values()).map(ToolUp::new).toList();
        all.forEach(one -> one.bench = all);
        return all;
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
        if (covered) {
            steps = 0;
            failures = 0;
        }
        if (open == null && cooldown <= 0 && !covered) {
            open = new ToolItem();
            ctx.journal().record(Category.PROJECT, open.describe(), "posted");
        } else if (open != null && !claimed && covered) {
            withdraw(ctx);
        }
        if (open != null && !claimed) {
            priority = hasTier(pack) ? SPARE : LACKING;
        }
    }

    private void read(BrainContext ctx) {
        int was = tier;
        tier = Tools.currentTier(family, ctx.gate());
        if (seen && tier != was) {
            // Another tool, another price: what the last one earned and waited says nothing of it.
            steps = 0;
            failures = 0;
            cooldown = 0;
        }
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

    private void withdraw(BrainContext ctx) {
        ctx.journal().record(Category.PROJECT, open.describe(), "withdrawn (tooled up)");
        open = null;
    }

    /** Covered at the tier the age names now, not the one the last beat read. */
    private boolean coveredNow(BrainContext ctx) {
        return Tools.covered(family, Tools.currentTier(family, ctx.gate()), ctx.percepts().inventory(),
                ctx.profile().d(ProfileAspect.HANDLING_SPARE_BELOW));
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

    /**
     * A run at one bench lets a tool it could not make go, so success is judged here: this family
     * still short sits out the cooldown as a failure would, and a family the run made is withdrawn
     * rather than claimed for nothing.
     */
    @Override
    public void completed(WorkItem item, BrainContext ctx) {
        clear();
        if (coveredNow(ctx)) {
            steps = 0;
            failures = 0;
        } else {
            ctx.journal().record(Category.PROJECT, item.describe(), "not made, retry cooldown (" + backOff() + "t)");
        }
        for (ToolUp other : bench) {
            if (other != this && other.open != null && !other.claimed && other.coveredNow(ctx)) {
                other.withdraw(ctx);
            }
        }
    }

    @Override
    public void failed(WorkItem item, BrainContext ctx) {
        ctx.journal().record(Category.PROJECT, item.describe(), "unclaimed, retry cooldown (" + backOff() + "t)");
        clear();
    }

    /** One more failure in a row: sets the wait, {@link SetUp#cooldownAfter}'s, and returns it. */
    private int backOff() {
        cooldown = (int) SetUp.cooldownAfter(++failures);
        return cooldown;
    }

    @Override
    public int pricedOut(WorkItem item) {
        steps = Math.min(WorkToleranceCurve.MAX_STEPS, steps + 1);
        return steps;
    }

    @Override
    public int budgetSteps(WorkItem item) {
        return steps;
    }

    @Override
    public void lapsed(WorkItem item) {
        claimed = false;
    }

    @Override
    public String describe() {
        return "keep " + family.one() + (cooldown > 0 ? " (retry cooldown " + cooldown + "t)" : "");
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

        /** This family first, then every other one posted. */
        @Override
        public Task root() {
            List<Task> run = new ArrayList<>();
            run.add(new KeepTool(family));
            for (ToolUp other : bench) {
                if (other != ToolUp.this && other.open != null) {
                    run.add(new KeepTool(other.family));
                }
            }
            return run.size() == 1 ? run.get(0) : new AtOneBench(run);
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
            Inventory pack = ctx.percepts().inventory();
            if (Tools.covered(family, tier, pack, ctx.profile().d(ProfileAspect.HANDLING_SPARE_BELOW))) {
                return "has one";
            }
            return hasTier(pack) ? "worn" : "none at the tier";
        }
    }

    // ── continuity ───────────────────────────────────────────────────────────────────────────

    /** {@link KeepStocked}'s rhythm. The tier and the priority are re-read on the first tick. */
    @Override
    public KeepStocked.State snapshot() {
        return new KeepStocked.State(cooldown, clock, beats, open != null, claimed, steps, failures);
    }

    @Override
    public void restore(KeepStocked.State state) {
        this.cooldown = state.cooldown();
        this.clock = state.clock();
        this.beats = state.beats();
        this.open = state.wanting() ? new ToolItem() : null;
        this.claimed = state.claimed();
        this.steps = state.steps();
        this.failures = state.failures();
    }

    @Override
    public @Nullable WorkItem openItem() {
        return open;
    }
}
