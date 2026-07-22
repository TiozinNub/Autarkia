package dev.luizloyola.autarkia.core.brain.task;

import dev.luizloyola.autarkia.core.brain.act.ActuatorAccess;
import dev.luizloyola.autarkia.core.brain.act.MoveState;

/**
 * Walk to a cell: issues one {@link dev.luizloyola.autarkia.core.brain.act.Mover#moveTo} and
 * translates the mover's lifecycle into task status.
 *
 * <p><b>First-tick semantic — issue, don't read.</b> The first tick issues the order and returns
 * {@link TaskStatus#RUNNING} unconditionally; {@code state()} is consulted only from the second
 * tick on, because the port promises only that state reports the order's progress "from the next
 * tick on". Past the issuing tick an observed {@link MoveState#IDLE} can then mean exactly one
 * thing (someone else stopped the legs out from under us), so it is clearly a failure. The
 * cost is that a goto to the cell you already stand on takes two ticks.
 *
 * <p>Mover state on later ticks maps as: MOVING → RUNNING, ARRIVED → SUCCESS, FAILED → FAILED,
 * and IDLE → FAILED.
 */
public final class GoTo implements PrimitiveTask {
    private final int x;
    private final int y;
    private final int z;
    private boolean issued;

    public GoTo(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    @Override
    public TaskStatus tick(ActuatorAccess actuators) {
        if (!issued) {
            issued = true;
            actuators.mover().moveTo(x, y, z);
            return TaskStatus.RUNNING; // see class doc: issue, don't read
        }
        MoveState state = actuators.mover().state();
        switch (state) {
            case MOVING:
                return TaskStatus.RUNNING;
            case ARRIVED:
                return TaskStatus.SUCCESS;
            case FAILED:
                return TaskStatus.FAILED;
            case IDLE:
            default:
                // The mover was stopped out from under us — someone else took the legs; the
                // task cannot claim success.
                return TaskStatus.FAILED;
        }
    }

    @Override
    public void cancel(ActuatorAccess actuators) {
        // Idempotent because Mover.stop() absorbs the no-move case (see its doc) — safe before
        // the first tick, after arrival, or twice in a row.
        actuators.mover().stop();
    }

    @Override
    public String describe() {
        return "goto (" + x + ", " + y + ", " + z + ")";
    }
}
