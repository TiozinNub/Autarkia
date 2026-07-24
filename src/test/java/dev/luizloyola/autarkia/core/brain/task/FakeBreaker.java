package dev.luizloyola.autarkia.core.brain.task;

import dev.luizloyola.autarkia.core.brain.act.BlockBreaker;
import dev.luizloyola.autarkia.core.brain.act.BreakState;
import dev.luizloyola.autarkia.core.brain.sense.Pos;
import org.jspecify.annotations.Nullable;

/**
 * Scripted {@link BlockBreaker} for task tests: tests advance the "arm" by writing
 * {@link #state} directly, the way the body would.
 */
final class FakeBreaker implements BlockBreaker {
    @Nullable Pos target;
    /** Every begin target in order — the choreography assertions read this. */
    final java.util.List<Pos> targets = new java.util.ArrayList<>();
    boolean refuseBegin;
    /** Targets begin() refuses (simulated out-of-reach); refuseBegin refuses everything. */
    final java.util.Set<Pos> refuse = new java.util.HashSet<>();
    BreakState state = BreakState.IDLE;
    int begins;
    int aborts;

    @Override
    public boolean begin(Pos target) {
        this.begins++;
        if (refuseBegin || refuse.contains(target)) {
            return false;
        }
        this.targets.add(target);
        this.target = target;
        this.state = BreakState.BREAKING;
        return true;
    }

    @Override
    public BreakState state() {
        return state;
    }

    @Override
    public void abort() {
        aborts++;
        state = BreakState.IDLE;
    }
}
