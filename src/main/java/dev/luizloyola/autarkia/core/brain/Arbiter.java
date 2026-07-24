package dev.luizloyola.autarkia.core.brain;

import dev.luizloyola.autarkia.core.brain.board.WorkItem;
import dev.luizloyola.autarkia.core.brain.board.WorkSource;
import dev.luizloyola.autarkia.core.brain.instinct.Instinct;
import dev.luizloyola.autarkia.core.brain.task.ObtainItem;
import dev.luizloyola.autarkia.core.brain.task.TaskExecutor;
import dev.luizloyola.autarkia.core.brain.task.TaskStatus;
import dev.luizloyola.autarkia.core.log.Category;
import java.util.List;
import java.util.Locale;

/**
 * The always-on "what am I doing right now" component (brain design doc): every tick it weighs TWO
 * SPECIES OF DEMAND on one 0..1 scale — each {@link Instinct}'s live pressure (a drive: stateless
 * want, fresh root per grant, nothing to resume) and the claimed work item's priority (a
 * commitment: a specific owed thing with a lifecycle) — and keeps its one {@link TaskExecutor}
 * running the winner. {@link #STICKINESS} and {@link #PREEMPT} apply to both alike; only the
 * lifecycle differs (the work-loop design's table):
 *
 * <ul>
 *   <li><b>Preempted drive</b> → cancelled; a re-rolled root is its resume.</li>
 *   <li><b>Preempted commitment</b> → SUSPENDED: the tree is dropped, the claim kept, and it
 *       resumes as a fresh {@code ObtainItem} root — re-validation for free, since the
 *       achieve-goal re-reads the pack.</li>
 *   <li><b>Commitments never preempt mid-task</b>: a standing priority sits below
 *       {@link #PREEMPT}, so work waits for the task boundary.</li>
 *   <li><b>Boundaries report to the board</b>: SUCCESS → {@code complete}, FAILED → {@code fail}
 *       (unclaim; the board's cooldown paces the retry). Ties go to drives.</li>
 * </ul>
 *
 * <p>Tolerance follows the species: a drive's is desperation ({@link ToleranceCurve} of its
 * pressure), a commitment's a fixed budget from its priority ({@link #workTolerance} — decision:
 * Luiz, "decouples the wants from the needs"). Between {@link #active} and
 * {@link #runningCommitment} exactly one (or neither) names what the executor is running;
 * {@link #executor()} is the mod driver's manual entry point.
 */
public final class Arbiter {

    /**
     * Incumbency bonus added to the active demand's bid when comparing — the hysteresis that
     * stops 51/49 dithering. Commitment is the norm: "fixed until satisfied or overridden".
     */
    public static final double STICKINESS = 0.1;

    /**
     * Minimum RAW pressure a challenger needs to preempt a running task mid-flight. Below it, a
     * higher bidder still waits for the current task to finish; at or above it, an urgent drive
     * (a hungry-enough Eat, an imminent-threat Flee) cuts in immediately — suspending a running
     * commitment rather than killing it.
     */
    public static final double PREEMPT = 0.6;

    /**
     * The commitment cost budget: {@code BASE + PER_PRIORITY × priority} walk-blocks. Flat by
     * design — no desperation plateau, no unbounded spend: the standing stock item (0.35) gets
     * ~75, a real errand's budget. Tuning knobs; the future config file maps onto them.
     */
    public static final double WORK_TOLERANCE_BASE = 40.0;
    public static final double WORK_TOLERANCE_PER_PRIORITY = 100.0;

    private final List<Instinct> instincts;
    private final WorkSource work;
    private final TaskExecutor executor = new TaskExecutor();

    /** Per-instinct cooldown counters (parallel to {@link #instincts}); {@code 0} means eligible. */
    private final int[] cooldowns;
    /** Last tick's pressures, cached for the ctx-less {@link #describe()}. */
    private final double[] lastPressures;

    /** The instinct whose root is currently running, or {@code null} (idle, commitment, manual). */
    private Instinct active;
    /** The active instinct's pressure as of the last arbitration — the tolerance source. */
    private double activePressure;
    /** The claimed work item — running or suspended; {@code null} when none is owed. */
    private WorkItem commitment;
    /** Whether the executor's current root is the commitment's (false + non-null = suspended). */
    private boolean runningCommitment;
    /** The last drive whose grant was journalled — spam guard; see {@link #grant}. */
    private Instinct lastGranted;

    /** A drives-only arbiter — the slice-1 shape; tests and any workless host use this. */
    public Arbiter(List<Instinct> instincts) {
        this(instincts, null);
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

        // 2. Top eligible drive by effective pressure (incumbent gets STICKINESS; ties -> earlier).
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

        // 2b. The commitment's bid: the held item's priority (sticky while running), or the
        //     board's best offer. Strictly-greater below: ties go to drives — the body first.
        double commitEffective = Double.NEGATIVE_INFINITY;
        WorkItem offer = null;
        if (work != null) {
            if (commitment != null) {
                commitEffective = commitment.priority() + (runningCommitment ? STICKINESS : 0.0);
            } else {
                offer = work.bestAvailable().orElse(null);
                if (offer != null) {
                    commitEffective = offer.priority();
                }
            }
        }

        // 3 & 4. Grant when idle; conditional preempt when busy.
        if (!executor.isBusy()) {
            if (commitEffective > topEffective && (commitment != null || offer != null)) {
                grantCommitment(offer, ctx);
            } else if (topIndex >= 0) {
                grant(topIndex, ctx);
            }
        } else if (runningCommitment) {
            // A drive may cut into running work (urgent only), which suspends, never kills.
            if (topIndex >= 0 && topEffective > commitEffective && lastPressures[topIndex] >= PREEMPT) {
                suspendCommitment(ctx, instincts.get(topIndex).describe(), lastPressures[topIndex]);
                grant(topIndex, ctx);
            }
        } else if (topIndex >= 0 && topIndex != activeIndex) {
            double activeEffective = activeIndex >= 0
                    ? lastPressures[activeIndex] + STICKINESS
                    : Double.NEGATIVE_INFINITY; // a manual task yields to any bidder that preempts
            if (topEffective > activeEffective && lastPressures[topIndex] >= PREEMPT) {
                grant(topIndex, ctx);
            }
        }

        // Keep the tolerance source current for an incumbent drive that kept running.
        if (active != null) {
            activePressure = lastPressures[indexOf(active)];
        }

        // 5. Run one step; detect a task boundary crossed this tick and react to its outcome.
        boolean busyBefore = executor.isBusy();
        executor.tick(ctx);
        if (busyBefore && !executor.isBusy()) {
            TaskStatus last = executor.lastStatus().orElse(null);
            if (runningCommitment) {
                if (last == TaskStatus.FAILED) {
                    // PROJECT log: the goal is out of reach right now — unclaim; the board's
                    // cooldown paces the retry (and is where the escalation flag will hang).
                    ctx.journal().record(Category.PROJECT, commitment.describe(),
                            "failed — unclaimed (board cooldown)");
                    work.fail(commitment);
                } else {
                    ctx.journal().record(Category.PROJECT, commitment.describe(), "completed");
                    work.complete(commitment);
                }
                commitment = null;
                runningCommitment = false;
            } else if (active != null && last == TaskStatus.FAILED) {
                // BRAIN log: only failures — the interesting signal.
                ctx.journal().record(Category.BRAIN, active.describe(), "failed");
                cooldowns[indexOf(active)] = active.failCooldown();
            }
            active = null; // next tick's idle-grant re-arbitrates
        }
    }

    /**
     * The current cost ceiling for method selection: a running commitment spends its fixed
     * {@link #workTolerance budget}; a drive spends {@link ToleranceCurve} of its pressure;
     * nothing active means unbounded (idle re-arbitrates next tick; manual driving answers to
     * no pressure).
     */
    public double costTolerance() {
        if (runningCommitment) {
            return workTolerance(commitment.priority());
        }
        return active == null ? Double.POSITIVE_INFINITY : ToleranceCurve.tolerance(activePressure);
    }

    /** The flat commitment budget for a work priority — see {@link #WORK_TOLERANCE_BASE}. */
    public static double workTolerance(double priority) {
        return WORK_TOLERANCE_BASE + WORK_TOLERANCE_PER_PRIORITY * priority;
    }

    /** The executor the arbiter drives — the mod driver's manual-mode entry point and status source. */
    public TaskExecutor executor() {
        return executor;
    }

    /**
     * One line per instinct (name, pressure, active/cooldown tags), then the commitment line
     * when one is owed — {@code work: acquire logs x16 (active|suspended)} — then the
     * executor's own describe. The brain's "why is she doing that?" answer, ctx-less.
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
        if (commitment != null) {
            sb.append("work: ").append(commitment.describe())
                    .append(runningCommitment ? " (active)" : " (suspended)").append('\n');
        }
        sb.append(executor.describe());
        return sb.toString();
    }

    // --- internals -------------------------------------------------------------------------------

    /** Install instinct {@code i}'s fresh root as the running task, recording it as active. */
    private void grant(int i, BrainContext ctx) {
        Instinct instinct = instincts.get(i);
        // BRAIN log: journal only a genuine change of drive — not the incumbent re-granting
        // itself after each SUCCESS (that would swamp the ring with wander re-rolls).
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

    /**
     * Take (or resume) the commitment: a fresh claim journals its lifecycle head; a resume just
     * says so. Either way the root is a FRESH {@code ObtainItem} — the achieve-goal re-reads
     * the world, so logs gathered before an interruption count and the remainder re-plans.
     */
    private void grantCommitment(WorkItem offer, BrainContext ctx) {
        if (commitment == null) {
            commitment = offer;
            work.claim(offer);
            ctx.journal().record(Category.PROJECT, offer.describe(),
                    String.format(Locale.ROOT, "claimed (priority %.2f)", offer.priority()));
            ctx.journal().record(Category.PROJECT, offer.describe(), "started");
        } else {
            ctx.journal().record(Category.PROJECT, commitment.describe(), "resumed");
        }
        runningCommitment = true;
        active = null;
        lastGranted = null; // the next drive grant is a genuine change again — journal it
        executor.run(new ObtainItem(commitment.spec(), commitment.count()), ctx);
    }

    /** The claim survives; only the task tree dies (the follow-up {@link #grant} cancels it). */
    private void suspendCommitment(BrainContext ctx, String byWhom, double pressure) {
        ctx.journal().record(Category.PROJECT, commitment.describe(),
                String.format(Locale.ROOT, "suspended (by %s %.2f)", byWhom, pressure));
        runningCommitment = false;
    }

    private int indexOf(Instinct instinct) {
        return instinct == null ? -1 : instincts.indexOf(instinct);
    }
}
