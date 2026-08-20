package dev.luizloyola.autarkia.core.board;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.board.WorkItem;
import dev.luizloyola.anima.core.brain.task.PutAwaySurplus;
import dev.luizloyola.anima.core.brain.task.Task;
import dev.luizloyola.anima.core.inv.Surplus;
import dev.luizloyola.anima.core.log.Category;
import java.util.List;
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
 */
public final class StowSurplus implements PersonalProject {

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
     * ever without being taken. Clear-area posts at 0.5, which is what keeps tidying from
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
        boolean worthATrip = cargo(ctx) >= SURPLUS_SLOTS;
        if (open == null && cooldown <= 0 && worthATrip) {
            open = new StowItem();
            ctx.journal().record(Category.PROJECT, open.describe(), "posted");
        } else if (open != null && !claimed && !worthATrip) {
            // Only while UNCLAIMED, for KeepStocked's reason: an errand somebody is already
            // walking to is theirs to finish, and yanking it teaches them to ignore the board.
            ctx.journal().record(Category.PROJECT, open.describe(), "withdrawn (nothing spare)");
            open = null;
        }
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
        public double priority() {
            return PRIORITY;
        }

        @Override
        public Task root() {
            return new PutAwaySurplus();
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
