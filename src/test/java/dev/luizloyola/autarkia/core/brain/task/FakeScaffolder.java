package dev.luizloyola.autarkia.core.brain.task;

import dev.luizloyola.autarkia.core.brain.act.ScaffoldState;
import dev.luizloyola.autarkia.core.brain.act.Scaffolder;

/**
 * Scripted {@link Scaffolder}: {@code up} succeeds (and goes RISING) unless refused; the
 * test plays the body — flip {@link #state} to RISEN and raise the fake position itself.
 */
final class FakeScaffolder implements Scaffolder {
    ScaffoldState state = ScaffoldState.IDLE;
    boolean refuse;
    int ups;
    String lastItem;

    @Override
    public boolean up(String itemId) {
        if (refuse || state == ScaffoldState.RISING) {
            return false;
        }
        ups++;
        lastItem = itemId;
        state = ScaffoldState.RISING;
        return true;
    }

    @Override
    public ScaffoldState state() {
        return state;
    }

    @Override
    public void abort() {
        state = ScaffoldState.IDLE;
    }
}
