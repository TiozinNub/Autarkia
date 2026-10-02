package dev.luizloyola.autarkia.core.board;

import dev.luizloyola.anima.core.brain.board.WorkItem;

/**
 * A project that keeps the budget its own items earn by being priced out
 * ({@link dev.luizloyola.anima.core.brain.WorkToleranceCurve}). The board files a party project's
 * under its {@link WorkKey}; a personal project has none, so it keeps them itself and saves them
 * with its rhythm.
 */
interface GrowsBudget {

    /** {@code item} was priced out once more: the steps it has earned now, capped. */
    int pricedOut(WorkItem item);

    int budgetSteps(WorkItem item);
}
