package dev.luizloyola.autarkia.core.brain.act;

/**
 * Where the breaking arm is in its lifecycle — the exact shape of {@link ConsumeState} for the
 * break domain. Read by {@code BreakBlock} on the ticks after a successful
 * {@link BlockBreaker#begin}; written by the body-side breaker.
 */
public enum BreakState {
    /** No break in progress — nothing was begun, or {@link BlockBreaker#abort} ended it. */
    IDLE,
    /** Mid-break: progress is accumulating (clients see the crack animation deepen). */
    BREAKING,
    /** The block broke and its drops entered the world — the task observing this has nothing
     *  left to do but report success (collection is someone else's job). */
    FINISHED,
    /** The last break died: the block changed under the arm, moved out of reach, or turned out
     *  unbreakable. */
    FAILED
}
