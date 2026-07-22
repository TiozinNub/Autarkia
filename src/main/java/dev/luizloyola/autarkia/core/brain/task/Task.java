package dev.luizloyola.autarkia.core.brain.task;

/**
 * A node of the task tree — sealed over exactly two kinds: {@link PrimitiveTask} does (an
 * executable leaf state machine), {@link CompoundTask} decides (a goal achieved by one of several
 * {@link Method}s). New behavior arrives as new primitives and methods, never a third node kind.
 *
 * <p>{@link Method#decompose} returns freshly built {@code Task} instances: a compound expands
 * lazily, against <em>current</em> percepts, never resuming stale ones.
 */
public sealed interface Task permits PrimitiveTask, CompoundTask {
}
