package dev.luizloyola.autarkia.core.brain.task;

import dev.luizloyola.autarkia.core.brain.act.MoveState;
import dev.luizloyola.autarkia.core.brain.act.Mover;
import dev.luizloyola.autarkia.core.nav.Gait;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Test double for the {@link Mover} port. Deliberately DUMB about state — {@code moveTo} does not
 * flip it to MOVING, so a pre-set ARRIVED survives the issuing call: that is what makes the GoTo
 * first-tick test meaningful. Records the gait of the latest order ({@link #lastGait}), which the
 * 3-arg {@link Mover#moveTo(int, int, int)} always threads through as {@link Gait#WALK}.
 */
final class FakeMover implements Mover {
    /** Ordered call log, e.g. {@code "moveTo(1, 2, 3)"}, {@code "stop"} — for sequencing asserts. */
    final List<String> events = new ArrayList<>();
    int moveToCalls;
    int stopCalls;
    int lastX;
    int lastY;
    int lastZ;
    Gait lastGait;
    private MoveState state = MoveState.IDLE;

    void setState(MoveState state) {
        this.state = state;
    }

    @Override
    public void moveTo(int x, int y, int z, Gait gait) {
        moveToCalls++;
        lastX = x;
        lastY = y;
        lastZ = z;
        lastGait = gait;
        events.add("moveTo(" + x + ", " + y + ", " + z
                + (gait == Gait.WALK ? "" : ", " + gait.name().toLowerCase(Locale.ROOT)) + ")");
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
