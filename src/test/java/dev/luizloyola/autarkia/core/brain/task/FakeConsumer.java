package dev.luizloyola.autarkia.core.brain.task;

import dev.luizloyola.autarkia.core.brain.act.ConsumeState;
import dev.luizloyola.autarkia.core.brain.act.ItemConsumer;
import java.util.ArrayList;
import java.util.List;

/**
 * Test double for the {@link ItemConsumer} port, scripted like {@link FakeMover}. Deliberately
 * DUMB about state: {@code begin} does not flip it to CONSUMING, so a pre-set FINISHED survives
 * the call — which is what makes the ConsumeItem first-tick test meaningful.
 */
final class FakeConsumer implements ItemConsumer {
    /** Ordered call log, e.g. {@code "begin(14)"}, {@code "abort"} — for sequencing asserts. */
    final List<String> events = new ArrayList<>();
    int beginCalls;
    int abortCalls;
    int lastSlot = -1;
    /** Script what {@link #begin} answers — {@code false} = "nothing to eat there". */
    boolean beginResult = true;
    private ConsumeState state = ConsumeState.IDLE;

    void setState(ConsumeState state) {
        this.state = state;
    }

    @Override
    public boolean begin(int slot) {
        beginCalls++;
        lastSlot = slot;
        events.add("begin(" + slot + ")");
        return beginResult;
    }

    @Override
    public ConsumeState state() {
        return state;
    }

    @Override
    public void abort() {
        abortCalls++;
        events.add("abort");
        state = ConsumeState.IDLE; // the port contract: state returns to IDLE
    }
}
