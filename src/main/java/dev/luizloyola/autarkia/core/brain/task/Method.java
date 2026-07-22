package dev.luizloyola.autarkia.core.brain.task;

import dev.luizloyola.autarkia.core.brain.BrainContext;
import java.util.List;

/**
 * One way to achieve a {@link CompoundTask}: it perceives but never acts — acting is what the
 * {@link PrimitiveTask}s it decomposes into are for. The {@link TaskExecutor} compares methods by
 * {@link #applicable} + {@link #estimateCost} (cheap, no expansion) and expands only the winner.
 *
 * <p>Contract: {@link #decompose} is only ever called immediately after {@link #applicable} returned
 * {@code true} against the same context, in the same tick.
 */
public interface Method {
    /** Whether this way is usable right now — a cheap percept check, e.g. "is any carried stack edible?". */
    boolean applicable(BrainContext ctx);

    /**
     * Cheap cost heuristic for comparing applicable methods — path distance, staleness, risk —
     * with no expansion needed to compare. Re-read on every (re-)selection, never cached: the
     * world may have changed since the last look. Lower wins; ties go to the earlier entry in
     * {@link CompoundTask#methods()}.
     */
    double estimateCost(BrainContext ctx);

    /**
     * The subtasks (compound or primitive, freshly built — see {@link Task}) that carry this way
     * out, run in order. An empty list means the goal already holds — the method trivially
     * succeeds.
     */
    List<Task> decompose(BrainContext ctx);

    /**
     * One-line summary for the debug readout, e.g. {@code "eat from inventory"} — shown between
     * its compound and its subtasks in {@link TaskExecutor#describe()}'s expansion path.
     */
    String describe();
}
