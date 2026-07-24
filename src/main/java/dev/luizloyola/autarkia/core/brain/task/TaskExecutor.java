package dev.luizloyola.autarkia.core.brain.task;

import dev.luizloyola.autarkia.core.brain.BrainContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Runs one task tree at a time — the execution slot of the task layer, a recursive-HTN walker.
 * The root may be a {@link PrimitiveTask} or a {@link CompoundTask}. Ticked by the mod
 * {@code BrainDriver} from {@code serverAiStep} before the Navigator ticks, so actuator orders a
 * task issues in its tick are acted on that same tick.
 *
 * <p><b>Tree execution.</b> The expansion path is a stack of frames — one per compound reached,
 * holding the chosen {@link Method}, its subtask sequence, the cursor, and the methods this
 * visit already burned. Per tick it descends from the current position, expanding compounds
 * lazily (cheapest applicable not-yet-failed method wins, ties to list order; an empty decompose
 * trivially succeeds), and ticks exactly one primitive — the deepest. Expansion is free; acting
 * is metered.
 *
 * <p><b>The one failure rule.</b> A subtask's FAILED fails its parent frame's method: the method
 * is marked tried and the compound re-selects among its remaining applicable methods, re-scored
 * against the fresh context. No method left → the compound FAILS and bubbles the same way; the
 * root failing is terminal and remembered.
 *
 * <p>Lifecycle, all in service of "the body has one set of legs":
 * <ul>
 *   <li>{@link #run} while busy cancels the incumbent first — the deepest primitive holds the
 *       actuators — and drops the whole stack before installing the newcomer, without ticking
 *       it;</li>
 *   <li>a terminal status clears the slot and is remembered (root description + status);</li>
 *   <li>{@link #cancel} clears without recording, so a cancelled tree cannot overwrite the last
 *       real outcome. Only the deepest primitive is cancelled — compounds and methods never
 *       touch actuators.</li>
 * </ul>
 */
public final class TaskExecutor {

    /**
     * Maximum expansion depth (nested compound frames); today's content is depth ≤ 2. A
     * decomposition cycle can never hang the executor: expanding past this bound FAILS that branch
     * as an ordinary method failure, never an exception.
     */
    public static final int MAX_DEPTH = 8;

    /**
     * Safety cap on an {@link AchieveTask}'s selection rounds within one activation — a
     * backstop against pathological zero-progress cycles (a method that "succeeds" without
     * moving the world toward the goal), far above any real errand (a 16-log stock is
     * 3–5 rounds). Hitting it fails the compound like any other dead end.
     */
    public static final int ACHIEVE_ROUNDS_CAP = 32;

    /**
     * One expanded compound on the path: the chosen method, its subtask sequence and cursor, and
     * which methods this visit has already burned. {@code methods()} is captured once per visit
     * so the tried-marks index a stable list; a later re-visit (the parent re-expanding) starts a
     * fresh frame with everything untried again — failure memory is per-visit.
     */
    private static final class Frame {
        final CompoundTask compound;
        final List<Method> methods;
        final boolean[] tried;
        Method method;
        int methodIndex;
        List<Task> subtasks;
        int index; // invariant between ticks: < subtasks.size() (exhausted frames pop eagerly)
        /** Achieve-frames only: selection rounds burned this activation (see ACHIEVE_ROUNDS_CAP). */
        int rounds;

        /** Applicable-but-priced-out count from the last {@code choose} pass — the "why" of a
         *  no-method failure: nothing fit, or nothing was affordable. */
        int pricedOut;

        Frame(CompoundTask compound) {
            this.compound = compound;
            this.methods = compound.methods();
            this.tried = new boolean[this.methods.size()];
        }
    }

    private Task root;
    /** The expansion path, outermost first; empty while the current position is the root itself. */
    private final List<Frame> stack = new ArrayList<>();
    private String lastDescription;
    private TaskStatus lastStatus;
    /**
     * The DEEPEST cause of the last FAILED root — first writer wins while a failure bubbles, so
     * the origin survives the cascade ("why?" must be printable: a failed primitive's name, a
     * compound with no applicable way, everything priced out, the depth or rounds cap). Cleared
     * on {@link #run} and on a SUCCESS terminal.
     */
    private String failureReason;

    /**
     * Install a tree as the one being executed, preempting (cancelling) any incumbent first.
     * Does not tick the newcomer — the next {@link #tick} does, so a tree's first expansion and
     * first primitive decision always happen at the normal point in the tick order.
     */
    public void run(Task task, BrainContext ctx) {
        releaseAndClear(ctx);
        root = task;
        failureReason = null;
    }

    /**
     * Cancel and clear the current tree, if any: the deepest primitive (the only node holding
     * actuators) is cancelled and the stack dropped. Records nothing: {@link #describe} goes back
     * to an idle reading with the previous terminal outcome (if any) intact.
     */
    public void cancel(BrainContext ctx) {
        releaseAndClear(ctx);
    }

    /**
     * One executor tick: a no-op when idle; otherwise descend (expanding compounds as needed —
     * see the class doc) and tick the deepest primitive once, then advance or bubble its terminal
     * status. A tree can finish without any primitive tick this call (trivial expansions, or
     * every method failing during descent); then the outcome is recorded.
     */
    public void tick(BrainContext ctx) {
        if (root == null) {
            return;
        }
        PrimitiveTask leaf = descend(ctx);
        if (leaf == null) {
            return; // the tree reached a terminal during expansion; recorded already
        }
        TaskStatus status = leaf.tick(ctx);
        if (status == TaskStatus.SUCCESS) {
            succeedCurrent(ctx);
        } else if (status == TaskStatus.FAILED) {
            noteFailure(leaf.describe() + " failed");
            failCurrent(ctx);
        }
    }

    public boolean isBusy() {
        return root != null;
    }

    /**
     * The status the last ROOT to run finished with — empty until any root has reached a terminal,
     * and unchanged by {@link #cancel}. The arbiter reads it right after {@link #tick}: a FAILED
     * root puts its instinct on the fail-cooldown, a SUCCESS just re-arbitrates.
     */
    public Optional<TaskStatus> lastStatus() {
        return Optional.ofNullable(lastStatus);
    }

    /** The deepest cause of the last FAILED root, for the journal's "why" — see {@link #failureReason}. */
    public Optional<String> failureReason() {
        return Optional.ofNullable(failureReason);
    }

    /**
     * The debug readout: while busy, the expansion path — each frame's compound and chosen method,
     * then the current node, e.g. {@code "running: satisfy hunger > eat from inventory > consume
     * slot 14"}; idle, {@code "idle (last: <root> -> <status>)"} or plain {@code "idle"}.
     */
    public String describe() {
        if (root == null) {
            if (lastStatus == null) {
                return "idle";
            }
            return "idle (last: " + lastDescription + " -> " + lastStatus + ")";
        }
        StringBuilder path = new StringBuilder("running: ");
        for (Frame frame : stack) {
            path.append(frame.compound.describe()).append(" > ").append(frame.method.describe()).append(" > ");
        }
        path.append(describeNode(currentNode()));
        return path.toString();
    }

    // --- internals -------------------------------------------------------------------------------

    /** The node execution is at: the top frame's current subtask, or the root before any expansion. */
    private Task currentNode() {
        if (stack.isEmpty()) {
            return root;
        }
        Frame top = stack.get(stack.size() - 1);
        return top.subtasks.get(top.index);
    }

    /** {@link Task} is a pure marker (see its doc); both kinds describe themselves. */
    private static String describeNode(Task task) {
        return task instanceof PrimitiveTask primitive ? primitive.describe() : ((CompoundTask) task).describe();
    }

    /**
     * Walk from the current position down to a primitive, expanding compounds as they are
     * reached. Trivial method successes (empty decompose) cascade up and descent continues past
     * them; expansion failures (nothing applicable, depth exceeded) bubble as method failures and
     * descent continues into whatever replacement gets chosen. Returns {@code null} when the tree
     * reached a terminal outcome instead of a primitive (already recorded). Terminates: every
     * iteration either burns a method mark, advances a cursor, or moves the depth-bounded stack —
     * all finite.
     */
    private PrimitiveTask descend(BrainContext ctx) {
        while (root != null) {
            Task node = currentNode();
            if (node instanceof PrimitiveTask primitive) {
                return primitive;
            }
            CompoundTask compound = (CompoundTask) node;
            if (compound instanceof AchieveTask achieve && achieve.satisfied(ctx)) {
                succeedCurrent(ctx); // the condition already holds — achieved without a frame
                continue;
            }
            if (stack.size() >= MAX_DEPTH) {
                noteFailure(compound.describe() + ": depth cap (" + MAX_DEPTH + ")");
                failCurrent(ctx); // the branch fails, as a method failure in the parent — no exception
                continue;
            }
            Frame frame = new Frame(compound);
            if (!chooseRound(frame, ctx)) {
                noteFailure(noWay(frame, ctx));
                failCurrent(ctx); // no applicable method at all
                continue;
            }
            if (frame.subtasks.isEmpty()) {
                succeedCurrent(ctx); // trivial success: the goal already holds, the compound is done
                continue;
            }
            stack.add(frame);
        }
        return null;
    }

    /**
     * Select the cheapest applicable not-yet-tried method of {@code frame} whose cost fits the
     * current {@link BrainContext#costTolerance()} and expand it (cursor reset). Applicability and
     * cost are read NOW, never cached; ties go to the earlier list entry, keeping selection
     * deterministic.
     *
     * <p><b>The tolerance gate.</b> A method costing more than the tolerance is skipped as if
     * inapplicable and not marked tried, since a higher tolerance later could pick it; everything
     * priced out fails the compound. A raw potato ({@code EatLastResort}, priced 80) waits while
     * merely hungry (tolerance 60) and is eaten while starving (tolerance ∞).
     */
    private boolean choose(Frame frame, BrainContext ctx) {
        Method best = null;
        int bestIndex = -1;
        double bestCost = 0.0;
        double tolerance = ctx.costTolerance();
        frame.pricedOut = 0;
        for (int i = 0; i < frame.methods.size(); i++) {
            if (frame.tried[i]) {
                continue;
            }
            Method method = frame.methods.get(i);
            if (!method.applicable(ctx)) {
                continue;
            }
            double cost = method.estimateCost(ctx);
            if (cost > tolerance) {
                frame.pricedOut++;
                continue; // priced out of the current tolerance — inapplicable in effect
            }
            if (best == null || cost < bestCost) {
                best = method;
                bestIndex = i;
                bestCost = cost;
            }
        }
        if (best == null) {
            return false;
        }
        frame.method = best;
        frame.methodIndex = bestIndex;
        frame.subtasks = best.decompose(ctx); // per the Method contract: right after applicable()
        frame.index = 0;
        return true;
    }

    /**
     * The node at the current position finished with SUCCESS: advance the parent frame's
     * sequence; a sequence exhausted means a DO-compound succeeds (cascading to its parent),
     * while an ACHIEVE-compound checks its condition — satisfied cascades the same way,
     * unsatisfied starts a fresh selection round in the same frame (tried-marks reset, methods
     * re-scored against the changed world, depth unchanged) until the rounds cap or method
     * exhaustion fails it like any dead end. Reaching past the root records the terminal outcome.
     */
    private void succeedCurrent(BrainContext ctx) {
        while (true) {
            if (stack.isEmpty()) {
                record(TaskStatus.SUCCESS);
                return;
            }
            Frame top = stack.get(stack.size() - 1);
            top.index++;
            if (top.index < top.subtasks.size()) {
                return;
            }
            if (top.compound instanceof AchieveTask achieve) {
                if (achieve.satisfied(ctx)) {
                    stack.remove(stack.size() - 1); // achieved -> success cascades to the parent
                    continue;
                }
                if (++top.rounds >= ACHIEVE_ROUNDS_CAP) {
                    noteFailure(top.compound.describe() + ": rounds cap (no progress)");
                    stack.remove(stack.size() - 1);
                    failCurrent(ctx); // the zero-progress backstop: fails like any dead end
                    return;
                }
                java.util.Arrays.fill(top.tried, false); // fresh round: every method eligible again
                if (chooseRound(top, ctx)) {
                    return;
                }
                noteFailure(noWay(top, ctx));
                stack.remove(stack.size() - 1);
                failCurrent(ctx); // nothing applicable or affordable is left -> the goal is out of reach
                return;
            }
            stack.remove(stack.size() - 1); // sequence exhausted -> the compound itself succeeded
        }
    }

    /**
     * {@link #choose}, with the achieve refinement: an empty decompose cannot make an UNSATISFIED
     * condition true (the executor checks {@code satisfied()} itself — a method claiming trivial
     * success here is just making no progress), so for achieve-frames such picks are burned and
     * selection continues. Do-frames pass through untouched — their empty decompose is trivial
     * success, per the {@link Method} contract.
     */
    private boolean chooseRound(Frame frame, BrainContext ctx) {
        if (!(frame.compound instanceof AchieveTask)) {
            return choose(frame, ctx);
        }
        while (choose(frame, ctx)) {
            if (!frame.subtasks.isEmpty()) {
                return true;
            }
            frame.tried[frame.methodIndex] = true;
        }
        return false;
    }

    /**
     * The node at the current position FAILED: the parent frame's method is marked tried and the
     * parent re-selects (re-scored, fresh context). A parent with no method left fails itself and
     * the bubbling continues upward; a replacement that decomposes empty flips the failure into
     * that compound's trivial success. Reaching past the root records the terminal FAILED.
     */
    private void failCurrent(BrainContext ctx) {
        while (true) {
            if (stack.isEmpty()) {
                record(TaskStatus.FAILED);
                return;
            }
            Frame parent = stack.get(stack.size() - 1);
            parent.tried[parent.methodIndex] = true; // the chosen method died with its subtask
            if (chooseRound(parent, ctx)) {
                if (parent.subtasks.isEmpty()) {
                    stack.remove(stack.size() - 1);
                    succeedCurrent(ctx); // the replacement trivially succeeds -> so does the compound
                }
                return;
            }
            stack.remove(stack.size() - 1); // methods exhausted -> the compound fails -> bubble
        }
    }

    /** Terminal outcome at the root: remember it (root description + status) and clear the slot. */
    private void record(TaskStatus status) {
        lastDescription = describeNode(root);
        lastStatus = status;
        if (status == TaskStatus.SUCCESS) {
            failureReason = null;
        }
        root = null;
        stack.clear();
    }

    /** First writer wins: the deepest origin of a bubbling failure is the one worth printing. */
    private void noteFailure(String reason) {
        if (failureReason == null) {
            failureReason = reason;
        }
    }

    /** The no-method message, split by cause: nothing applicable vs everything unaffordable. */
    private String noWay(Frame frame, BrainContext ctx) {
        if (frame.pricedOut > 0) {
            return frame.compound.describe() + ": no affordable way (" + frame.pricedOut
                    + " priced out at tolerance " + Math.round(ctx.costTolerance()) + ")";
        }
        return frame.compound.describe() + ": no applicable way";
    }

    /**
     * Preemption/cancel plumbing: cancel the deepest primitive if the current position holds one
     * (only primitives touch actuators; an unexpanded compound holds nothing), then drop the
     * whole tree. Never records — callers decide whether anything is remembered.
     */
    private void releaseAndClear(BrainContext ctx) {
        if (root == null) {
            return;
        }
        Task node = currentNode();
        if (node instanceof PrimitiveTask primitive) {
            primitive.cancel(ctx);
        }
        stack.clear();
        root = null;
    }
}
