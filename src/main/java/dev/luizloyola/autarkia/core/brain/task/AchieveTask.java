package dev.luizloyola.autarkia.core.brain.task;

import dev.luizloyola.autarkia.core.brain.BrainContext;

/**
 * A compound that names a CONDITION to make true, not a procedure to run once — HTN's achievement
 * goal, and where the loop lives for derived demand: an {@code ObtainItem(logs, 16)} sits mid-tree
 * under a craft or build goal, where neither the arbiter's root re-grant nor recursion depth can
 * drive iteration.
 *
 * <p>Executor semantics ({@link TaskExecutor}): {@link #satisfied} is checked before every method
 * selection, true meaning SUCCESS — including trivially at first expansion. A method subtree
 * succeeding starts a fresh round, tried-marks reset and selection re-scored; a failing one burns
 * its mark for the round (the livelock guard). No applicable affordable method, or the rounds cap,
 * FAILS the compound by the one-failure rule. Depth stays constant: re-selection replaces the
 * subtree, never stacks it.
 */
public interface AchieveTask extends CompoundTask {
    /** Whether the goal condition currently holds — cheap, read from live percepts. */
    boolean satisfied(BrainContext ctx);
}
