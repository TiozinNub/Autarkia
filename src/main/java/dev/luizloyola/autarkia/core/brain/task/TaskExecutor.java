package dev.luizloyola.autarkia.core.brain.task;

import dev.luizloyola.autarkia.core.brain.BrainContext;
import java.util.ArrayList;
import java.util.List;

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
     * Install a tree as the one being executed, preempting (cancelling) any incumbent first.
     * Does not tick the newcomer — the next {@link #tick} does, so a tree's first expansion and
     * first primitive decision always happen at the normal point in the tick order.
     */
    public void run(Task task, BrainContext ctx) {
        releaseAndClear(ctx);
        root = task;
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
            succeedCurrent();
        } else if (status == TaskStatus.FAILED) {
            failCurrent(ctx);
        }
    }

    public boolean isBusy() {
        return root != null;
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
            if (stack.size() >= MAX_DEPTH) {
                failCurrent(ctx); // the branch fails, as a method failure in the parent — no exception
                continue;
            }
            Frame frame = new Frame(compound);
            if (!choose(frame, ctx)) {
                failCurrent(ctx); // no applicable method at all
                continue;
            }
            if (frame.subtasks.isEmpty()) {
                succeedCurrent(); // trivial success: the goal already holds, the compound is done
                continue;
            }
            stack.add(frame);
        }
        return null;
    }

    /**
     * Select the cheapest applicable not-yet-tried method of {@code frame} and expand it (cursor
     * reset to its first subtask). Applicability and cost are read NOW, against the given context
     * — never cached from an earlier selection. Ties go to the earlier list entry (strict
     * less-than), keeping selection deterministic. Returns {@code false} when no method is left.
     */
    private boolean choose(Frame frame, BrainContext ctx) {
        Method best = null;
        int bestIndex = -1;
        double bestCost = 0.0;
        for (int i = 0; i < frame.methods.size(); i++) {
            if (frame.tried[i]) {
                continue;
            }
            Method method = frame.methods.get(i);
            if (!method.applicable(ctx)) {
                continue;
            }
            double cost = method.estimateCost(ctx);
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
     * sequence; a sequence exhausted means that compound SUCCEEDS, which advances its parent, and
     * so on — the success cascade. Reaching past the root records the terminal outcome.
     */
    private void succeedCurrent() {
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
            stack.remove(stack.size() - 1); // sequence exhausted -> the compound itself succeeded
        }
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
            if (choose(parent, ctx)) {
                if (parent.subtasks.isEmpty()) {
                    stack.remove(stack.size() - 1);
                    succeedCurrent(); // the replacement trivially succeeds -> so does the compound
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
        root = null;
        stack.clear();
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
