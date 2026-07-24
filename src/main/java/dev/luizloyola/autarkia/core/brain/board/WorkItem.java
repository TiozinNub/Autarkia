package dev.luizloyola.autarkia.core.brain.board;

import dev.luizloyola.autarkia.core.brain.BrainContext;
import dev.luizloyola.autarkia.core.brain.task.Task;

/**
 * One claimable unit of work. An {@code Instinct} bids continuous {@code pressure(ctx)} and is
 * never owed anything; a work item bids a fixed {@link #priority()} and once claimed is OWED —
 * suspension keeps the claim, resume builds a fresh {@link #root()} against the changed world,
 * prior progress counting automatically. The arbiter never learns what the work is.
 */
public interface WorkItem {
    /** The demand bid, on the instincts' 0..1 pressure scale — board policy, not body state. */
    double priority();

    /** A FRESH task tree that pursues this item — called anew on every grant and resume. */
    Task root();

    /** One-line name for journal and board readouts, e.g. {@code "acquire logs x16"}. */
    String describe();

    /** Short progress note for the resume/status lines, e.g. {@code "9/16 held"}; may be empty. */
    default String progress(BrainContext ctx) {
        return "";
    }
}
