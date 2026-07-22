package dev.luizloyola.autarkia.core.brain.act;

/**
 * Where the legs are in their lifecycle. Coarser than the mod Navigator's own state
 * machine: its PATHING (search in flight) and FOLLOWING (walking the path) both read as
 * {@link #MOVING} here, because a task cares only whether its order is being worked on, finished,
 * or dead.
 *
 * <p>Read by primitive tasks (e.g. {@code GoTo}) on the ticks after they issue a
 * {@link Mover#moveTo}; written by the mod-layer Mover as the Navigator underneath it advances.
 */
public enum MoveState {
    /** No move order in progress — nothing was asked, or {@link Mover#stop} abandoned it. */
    IDLE,
    /** The order is being worked on: path being computed or followed. */
    MOVING,
    /** The last order completed: the body stands at (or within arrival radius of) the goal cell. */
    ARRIVED,
    /** The last order died: goal unreachable, or the follower gave up after exhausting retries. */
    FAILED
}
