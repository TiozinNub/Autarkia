package dev.luizloyola.autarkia.core.brain.task;

import dev.luizloyola.autarkia.core.brain.act.MoveState;
import dev.luizloyola.autarkia.core.brain.act.Mover;
import java.util.ArrayList;
import java.util.List;

/**
 * Test double for the {@link Mover} port: logs every call (events, counters, last coords) and
 * scripts the state it reports. Deliberately DUMB — {@code moveTo} never flips state to MOVING;
 * only {@link #setState} and {@link #stop} (port contract: back to IDLE) do, so a pre-set ARRIVED
 * survives the issuing call and proves GoTo's first tick issues without reading.
 */
final class FakeMover implements Mover {
    /** Ordered call log, e.g. {@code "moveTo(1, 2, 3)"}, {@code "stop"} — for sequencing asserts. */
    final List<String> events = new ArrayList<>();
    int moveToCalls;
    int stopCalls;
    int lastX;
    int lastY;
    int lastZ;
    private MoveState state = MoveState.IDLE;

    void setState(MoveState state) {
        this.state = state;
    }

    @Override
    public void moveTo(int x, int y, int z) {
        moveToCalls++;
        lastX = x;
        lastY = y;
        lastZ = z;
        events.add("moveTo(" + x + ", " + y + ", " + z + ")");
    }

    @Override
    public MoveState state() {
        return state;
    }

    @Override
    public void stop() {
        stopCalls++;
        events.add("stop");
        state = MoveState.IDLE; // the port contract: state returns to IDLE
    }
}
