package dev.luizloyola.autarkia.core.board;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.history.Deed;
import dev.luizloyola.anima.core.brain.board.WorkItem;
import dev.luizloyola.anima.core.brain.task.PutAwaySurplus;
import dev.luizloyola.anima.core.brain.task.Task;
import dev.luizloyola.anima.core.inv.Surplus;
import dev.luizloyola.anima.core.log.Category;
import dev.luizloyola.anima.core.store.Depot;
import java.util.List;
import java.util.OptionalInt;
import org.jspecify.annotations.Nullable;

/**
 * Put the day's cargo away — the routine half of the stow arc, and the way a pack NORMALLY gets
 * cleared (decision: Luiz, 2026-08-20). Anima's unburden instinct is the safeguard beneath this,
 * for the case a single act fills the last slots; in ordinary play it never fires at all.
 *
 * <p><b>It lands between tasks by construction, not by a rule.</b> Work never preempts mid-flight —
 * only a drive past {@code mind.preempt} cuts a running errand — so a posted stow can only be
 * picked up when the body next asks the board what to do. A settler chopping a grove crosses the
 * line, finishes the tree they are on, and takes the trip on the way to the next one.
 *
 * <p>A condition, not an outcome: {@link #finished()} is always false, and the errand is WITHDRAWN
 * rather than completed when the pack drops back under the line by some other route.
 *
 * <p><b>Only home.</b> The load goes to the body's {@link BrainContext#depot() depot}, HOME's stores,
 * and with none nothing is posted: no base, no offloading (decision: Luiz, 2026-09-30). A scout
 * once stowed 458 items into a chest it built at a stop 450 blocks from where it settled.
 *
 * <p><b>Not into a full HOME</b> (Luiz, 2026-10-02: "surplus leaf litter should cause more
 * storage"): while HOME's stores have no empty slot, nothing is posted and an untaken errand is
 * withdrawn — the party's {@code storage} Direction is setting up the next chest, and a trip now
 * finds every chest full and ends in the hauler building one of its own beside it. Read from the
 * world each beat, so a restart holds nothing to forget.
 */
public final class StowSurplus implements PersonalProject {

    /**
     * How many empty slots a depot's stores have between them — installed by the mod, which can
     * read chests. Empty when it cannot say, and an unread HOME never holds a stow back.
     */
    public interface Room {
        OptionalInt free(Depot.Site site);
    }

    private static volatile Room room = site -> OptionalInt.empty();

    public static void install(@Nullable Room installed) {
        room = installed == null ? site -> OptionalInt.empty() : installed;
    }

    /**
     * Cargo slots that make a trip worth taking — a third of the pack, which is about a chest row
     * and change. Low enough that the safeguard stays unreached in ordinary play, high enough that
     * a settler is not commuting after every third log.
     */
    public static final int SURPLUS_SLOTS = 12;

    /** Ticks between re-evaluations, matching {@link KeepStocked}. */
    public static final int CHECK_INTERVAL = 100;

    /** Ticks a failed stow sits out. A failure usually means nowhere to put anything. */
    public static final int FAIL_COOLDOWN = 600;

    /**
     * Above the wander floor and well below real work. Both bounds are load-bearing and the lower
     * one was found in-world: at 0.1 this sat UNDER {@code instincts.wander_idle_pressure} (0.15),
     * so a settler with fourteen stacks of logs preferred to stroll and the errand was posted for
     * ever without being taken. A felling posts at 0.5, which is what keeps tidying from
     * outranking the job.
     */
    public static final double PRIORITY = 0.25;

    private final int offset;

    private @Nullable WorkItem open;
    private boolean claimed;
    private int cooldown;
    private int clock;
    private int beats;

    public StowSurplus(int offset) {
        this.offset = offset;
    }

    @Override
    public double priority() {
        return PRIORITY;
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
            return; // the warm-up beat: a newborn's first look lands before anything is carried
        }
        boolean home = ctx.depot().isPresent();
        boolean worthATrip = home && cargo(ctx) >= SURPLUS_SLOTS;
        // Asked only when it could change what happens: it reads every chest at HOME.
        boolean full = worthATrip && (open == null ? cooldown <= 0 : !claimed)
                && full(ctx.depot().get());
        if (open == null && cooldown <= 0 && worthATrip && !full) {
            open = new StowItem();
            ctx.journal().record(Category.PROJECT, open.describe(), "posted");
        } else if (open != null && !claimed && (!worthATrip || full)) {
            // Only while UNCLAIMED, for KeepStocked's reason: an errand somebody is already
            // walking to is theirs to finish, and yanking it teaches them to ignore the board.
            ctx.journal().record(Category.PROJECT, open.describe(),
                    !home ? "withdrawn (no home)" : full ? "withdrawn (HOME's stores are full)"
                            : "withdrawn (nothing spare)");
            open = null;
        }
    }

    private static boolean full(Depot.Site site) {
        OptionalInt free = room.free(site);
        return free.isPresent() && free.getAsInt() <= 0;
    }

    /** Storage slots holding things nobody has spoken for. */
    private static int cargo(BrainContext ctx) {
        return Surplus.slots(ctx.percepts().inventory(), ctx.reserved(),
                stack -> ctx.percepts().foods().of(stack).isPresent()).size();
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

    /** A lapsed lease says the worker was pulled away, not that the errand is undoable. */
    @Override
    public void lapsed(WorkItem item) {
        claimed = false;
    }

    @Override
    public String describe() {
        return "stow cargo past " + SURPLUS_SLOTS + " slots"
                + (cooldown > 0 ? " (retry cooldown " + cooldown + "t)" : "");
    }

    private void clear() {
        open = null;
        claimed = false;
    }

    /** The errand itself — the same goal the unburden instinct roots. */
    private static final class StowItem implements WorkItem {
        @Override
        public boolean buildsOnTheWay() {
            return true;
        }

        @Override
        public double priority() {
            return PRIORITY;
        }

        @Override
        public Task root() {
            return new PutAwaySurplus(0);
        }

        @Override
        public Deed doing() {
            return Deed.of(WorkDoings.STOWING);
        }

        @Override
        public String describe() {
            return "put away what nobody wants";
        }

        @Override
        public String progress(BrainContext ctx) {
            return cargo(ctx) + " slots of cargo";
        }
    }
}
