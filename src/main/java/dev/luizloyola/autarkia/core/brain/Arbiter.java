package dev.luizloyola.autarkia.core.brain;

import dev.luizloyola.autarkia.core.brain.board.WorkItem;
import dev.luizloyola.autarkia.core.brain.board.WorkSource;
import dev.luizloyola.autarkia.core.brain.instinct.Instinct;
import dev.luizloyola.autarkia.core.brain.task.TaskExecutor;
import dev.luizloyola.autarkia.core.brain.task.TaskStatus;
import dev.luizloyola.autarkia.core.log.Category;
import java.util.List;
import java.util.Locale;

/**
 * The always-on "what am I doing right now" component (brain design doc): every tick it reads each
 * {@link Instinct}'s pressure, picks the winner, and keeps its one {@link TaskExecutor} running
 * that winner's task tree. It also publishes the cost tolerance the executor gates methods by
 * ({@link #costTolerance()}), taken from the active drive's pressure through
 * {@link ToleranceCurve}. The executor is the arbiter's — {@link #executor()} is the mod driver's
 * manual entry point — and {@code active} names the instinct whose root is running ({@code null}
 * for idle or a manually-installed task).
 *
 * <h2>Per-tick arbitration ({@link #tick})</h2>
 * <ol>
 *   <li>Pressures are read once. An instinct sitting out the ticks its own
 *       {@link Instinct#failCooldown()} set after a FAILED root is INELIGIBLE, and its cooldown
 *       then ticks down by one.</li>
 *   <li>Top eligible bidder by EFFECTIVE pressure — the incumbent gets a {@link #STICKINESS}
 *       bonus, and ties go to the earlier instinct in the constructor list.</li>
 *   <li><b>Executor idle</b> → grant the top bidder; re-granting the incumbent after a SUCCESS is
 *       the continuous-behavior loop, {@code root()} called anew each time.</li>
 *   <li><b>Executor busy</b> → switch only if the top bidder is not the incumbent, beats it on
 *       effective pressure, and has RAW pressure at least {@link #PREEMPT}; below that it
 *       waits for the task boundary. Switching cancels the incumbent's task.</li>
 *   <li>The executor ticks once. On a boundary (busy before, idle after) a FAILED root sets its
 *       instinct's {@link #FAIL_COOLDOWN}, and {@code active} clears so the next tick's idle-grant
 *       re-arbitrates from scratch.</li>
 * </ol>
 * With no instincts, or none eligible, the executor still ticks — a manually-installed task must
 * keep running even under an all-cooling arbiter.
 */
public final class Arbiter {

    /** Incumbency bonus on the active instinct's bid — the hysteresis that stops 51/49 dithering. */
    public static final double STICKINESS = 0.1;

    /** Minimum RAW pressure to preempt mid-flight; below it a challenger waits for the boundary. */
    public static final double PREEMPT = 0.6;

    private final List<Instinct> instincts;
    private final WorkSource work;
    private final TaskExecutor executor = new TaskExecutor();

    /** Per-instinct cooldown counters (parallel to {@link #instincts}); {@code 0} means eligible. */
    private final int[] cooldowns;
    /** Last tick's pressures, cached for the ctx-less {@link #describe()}. */
    private final double[] lastPressures;

    /** The instinct whose root is currently running, or {@code null} (idle, or a manual task). */
    private Instinct active;
    /** Currently OWED — kept through suspensions: a preempt cancels the errand's tree, not the claim. */
    private WorkItem claimedItem;
    /** Whether the executor's current root belongs to {@link #claimedItem} (vs a drive's). */
    private boolean workRunning;
    /** The active instinct's pressure as of the last arbitration — the source for {@link #costTolerance()}. */
    private double activePressure;
    /** The last drive journalled, so a re-grant of the same drive does not spam the BRAIN log. */
    private Instinct lastGranted;

    /** An arbiter with no work source — drives only (tests, minimal rigs). */
    public Arbiter(List<Instinct> instincts) {
        this(instincts, WorkSource.NONE);
    }

    public Arbiter(List<Instinct> instincts, WorkSource work) {
        this.instincts = List.copyOf(instincts);
        this.work = work;
        this.cooldowns = new int[this.instincts.size()];
        this.lastPressures = new double[this.instincts.size()];
    }

    /** One arbitration + execution step — see the class doc for the exact semantics. */
    public void tick(BrainContext ctx) {
        int n = instincts.size();

        // 1. Eligibility is noted before the countdown, so a fresh cooldown buys that many ticks.
        boolean[] eligible = new boolean[n];
        for (int i = 0; i < n; i++) {
            eligible[i] = cooldowns[i] == 0;
            if (cooldowns[i] > 0) {
                cooldowns[i]--;
            }
            lastPressures[i] = instincts.get(i).pressure(ctx);
        }

        // 2. Top eligible bidder by effective pressure (incumbent gets STICKINESS; ties -> earlier).
        int activeIndex = indexOf(active);
        int topIndex = -1;
        double topEffective = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < n; i++) {
            if (!eligible[i]) {
                continue;
            }
            double effective = lastPressures[i] + (i == activeIndex ? STICKINESS : 0.0);
            if (effective > topEffective) { // strict > keeps the earlier entry on a tie
                topEffective = effective;
                topIndex = i;
            }
        }

        // 2b. The commitment bid: the item already owed, else the board's best offer — one more
        //     bidder on the same 0..1 scale (fixed board priority, not body pressure).
        WorkItem candidate = claimedItem != null
                ? claimedItem
                : work.bestAvailable(ctx).orElse(null);
        double workEffective = candidate == null
                ? Double.NEGATIVE_INFINITY
                : candidate.priority() + (workRunning ? STICKINESS : 0.0);

        // 3 & 4. Work never preempts mid-flight; a drive cuts a running errand only past the
        // PREEMPT bar, and the claim survives the cut.
        if (!executor.isBusy()) {
            if (candidate != null && workEffective > topEffective) {
                grantWork(candidate, ctx);
            } else if (topIndex >= 0) {
                grant(topIndex, ctx);
            }
        } else if (workRunning) {
            if (topIndex >= 0 && lastPressures[topIndex] >= PREEMPT && topEffective > workEffective) {
                ctx.journal().record(Category.PROJECT, claimedItem.describe(), String.format(Locale.ROOT,
                        "suspended (by %s %.2f)",
                        instincts.get(topIndex).describe(), lastPressures[topIndex]));
                workRunning = false;
                grant(topIndex, ctx); // run() cancels the errand's tree; the claim is KEPT
            }
        } else if (topIndex >= 0 && topIndex != activeIndex) {
            double activeEffective = activeIndex >= 0
                    ? lastPressures[activeIndex] + STICKINESS
                    : Double.NEGATIVE_INFINITY; // a manual task (no active instinct) yields to any real bidder... but only if it preempts
            if (topEffective > activeEffective && lastPressures[topIndex] >= PREEMPT) {
                grant(topIndex, ctx);
            }
        }

        // Keep the tolerance source current for an incumbent that kept running (wasn't re-granted).
        if (active != null) {
            activePressure = lastPressures[indexOf(active)];
        }

        // 5. Run one step; detect a task boundary crossed this tick and react to its outcome.
        boolean busyBefore = executor.isBusy();
        executor.tick(ctx);
        if (busyBefore && !executor.isBusy()) {
            if (workRunning) {
                // The errand reached a terminal: report to the board either way. Failure pacing
                // is the ITEM's (board cooldown), never an instinct cooldown.
                if (executor.lastStatus().orElse(null) == TaskStatus.FAILED) {
                    ctx.journal().record(Category.PROJECT, claimedItem.describe(), "failed"
                            + executor.failureReason().map(r -> " — " + r).orElse(""));
                    work.failed(claimedItem, ctx);
                } else {
                    ctx.journal().record(Category.PROJECT, claimedItem.describe(),
                            "completed (" + claimedItem.progress(ctx) + ")");
                    work.completed(claimedItem, ctx);
                }
                claimedItem = null;
                workRunning = false;
            } else if (active != null && executor.lastStatus().orElse(null) == TaskStatus.FAILED) {
                // BRAIN log: failures only — an unsatisfiable drive is the signal, and every
                // wander SUCCESS would be noise. The switch/take-over lines already mark what she
                // started.
                ctx.journal().record(Category.BRAIN, active.describe(), "failed"
                        + executor.failureReason().map(r -> " — " + r).orElse(""));
                cooldowns[indexOf(active)] = active.failCooldown();
            }
            active = null; // next tick's idle-grant re-arbitrates
        }
    }

    /**
     * The cost ceiling for method selection: {@link ToleranceCurve} of the active drive's pressure,
     * {@link Double#POSITIVE_INFINITY} when nothing is active.
     */
    public double costTolerance() {
        if (workRunning && claimedItem != null) {
            // Decoupled from the needs' desperation curve on purpose: a job is worth a fixed
            // effort, set by policy — see WorkToleranceCurve.
            return WorkToleranceCurve.tolerance(claimedItem.priority());
        }
        return active == null ? Double.POSITIVE_INFINITY : ToleranceCurve.tolerance(activePressure);
    }

    /** The executor the arbiter drives — the mod driver's manual-mode entry point and status source. */
    public TaskExecutor executor() {
        return executor;
    }

    /**
     * One line per instinct — name, last-tick pressure to 2dp, and a tag: {@code (active)} for the
     * running drive, {@code (cooldown Nt)} for one sitting out — then the executor's own describe.
     * The brain's "why is she doing that?" answer, ctx-less so a command can print it any time.
     */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < instincts.size(); i++) {
            Instinct instinct = instincts.get(i);
            sb.append(instinct.describe())
                    .append(String.format(Locale.ROOT, " %.2f", lastPressures[i]));
            if (instinct == active) {
                sb.append(" (active)");
            } else if (cooldowns[i] > 0) {
                sb.append(" (cooldown ").append(cooldowns[i]).append("t)");
            }
            sb.append('\n');
        }
        if (claimedItem != null) {
            sb.append("work: ").append(claimedItem.describe())
                    .append(workRunning ? " (active)" : " (suspended)").append('\n');
        }
        sb.append(executor.describe());
        return sb.toString();
    }

    // --- internals -------------------------------------------------------------------------------

    /** Engage (claim) or resume the work item: a fresh root either way — resume re-decomposes
     *  against the changed world, and achieve-goals count prior progress automatically. */
    private void grantWork(WorkItem item, BrainContext ctx) {
        if (claimedItem != item) {
            claimedItem = item;
            work.claimed(item, ctx);
            ctx.journal().record(Category.PROJECT, item.describe(), String.format(Locale.ROOT,
                    "claimed (priority %.2f)", item.priority()));
        } else {
            ctx.journal().record(Category.PROJECT, item.describe(),
                    "resumed (" + item.progress(ctx) + ")");
        }
        active = null;
        workRunning = true;
        executor.run(item.root(), ctx);
    }

    /** Install instinct {@code i}'s fresh root as the running task, recording it as active. */
    private void grant(int i, BrainContext ctx) {
        Instinct instinct = instincts.get(i);
        // BRAIN log: only a genuine change of drive, not the incumbent re-granting itself after
        // each SUCCESS — that would swamp the ring with wander re-rolls. A preempt is a switch
        // that cut a still-running task off.
        if (instinct != lastGranted) {
            boolean preempt = executor.isBusy() && active != null && active != instinct;
            ctx.journal().record(Category.BRAIN, instinct.describe(), String.format(Locale.ROOT,
                    "%s (pressure %.2f)", preempt ? "preempt" : "take over", lastPressures[i]));
            lastGranted = instinct;
        }
        active = instinct;
        activePressure = lastPressures[i];
        executor.run(instinct.root(ctx), ctx); // run() cancels any incumbent first
    }

    private int indexOf(Instinct instinct) {
        return instinct == null ? -1 : instincts.indexOf(instinct);
    }
}
