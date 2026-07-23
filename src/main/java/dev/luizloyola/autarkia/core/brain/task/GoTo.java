package dev.luizloyola.autarkia.core.brain.task;

import dev.luizloyola.autarkia.core.brain.BrainContext;
import dev.luizloyola.autarkia.core.brain.act.MoveState;
import dev.luizloyola.autarkia.core.nav.Gait;
import java.util.Locale;

/**
 * The first primitive: walk to a cell — the thinnest possible wrapper over
 * {@link dev.luizloyola.autarkia.core.brain.act.Mover#moveTo}, translating the mover's lifecycle
 * into task status.
 *
 * <p><b>First-tick semantic — issue, don't read.</b> The first tick issues the order and returns
 * {@link TaskStatus#RUNNING} unconditionally; {@code state()} is only consulted from the second
 * tick on, because the port promises only that state reports the order's progress "from the next
 * tick on". What that buys: past the issuing tick an observed {@link MoveState#IDLE} can mean
 * exactly one thing — someone else stopped the legs out from under us, and that is how the arbiter
 * preempts — so it is clearly a failure, never "haven't looked yet". It costs a goto to the cell
 * you already stand on two ticks instead of one.
 *
 * <p><b>Gait.</b> The {@link #GoTo(int, int, int, Gait) four-arg constructor} threads the requested
 * pace through to {@link dev.luizloyola.autarkia.core.brain.act.Mover#moveTo(int, int, int, Gait)}
 * — {@code FleeStep} orders {@link Gait#SPRINT}, {@code WanderStep} {@link Gait#STROLL}; the plain
 * constructor is an ordinary {@link Gait#WALK}.
 */
public final class GoTo implements PrimitiveTask {
    private final int x;
    private final int y;
    private final int z;
    private final Gait gait;
    private boolean issued;

    /** An ordinary walk to {@code (x, y, z)} — see the class doc on gait. */
    public GoTo(int x, int y, int z) {
        this(x, y, z, Gait.WALK);
    }

    /** @param gait see {@link dev.luizloyola.autarkia.core.brain.act.Mover#moveTo(int, int, int, Gait)} */
    public GoTo(int x, int y, int z, Gait gait) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.gait = gait;
    }

    @Override
    public TaskStatus tick(BrainContext ctx) {
        if (!issued) {
            issued = true;
            ctx.actuators().mover().moveTo(x, y, z, gait);
            return TaskStatus.RUNNING; // see class doc: issue, don't read
        }
        MoveState state = ctx.actuators().mover().state();
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
    public void cancel(BrainContext ctx) {
        // Idempotent because Mover.stop() absorbs the no-move case (see its doc) — safe before
        // the first tick, after arrival, or twice in a row.
        ctx.actuators().mover().stop();
    }

    @Override
    public String describe() {
        String pace = gait == Gait.WALK ? "" : " (" + gait.name().toLowerCase(Locale.ROOT) + ")";
        return "goto (" + x + ", " + y + ", " + z + ")" + pace;
    }
}
