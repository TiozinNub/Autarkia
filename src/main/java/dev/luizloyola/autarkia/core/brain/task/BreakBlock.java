package dev.luizloyola.autarkia.core.brain.task;

import dev.luizloyola.autarkia.core.brain.BrainContext;
import dev.luizloyola.autarkia.core.brain.act.BlockBreaker;
import dev.luizloyola.autarkia.core.brain.sense.Pos;

/**
 * Break one block — the {@code ConsumeItem} shape on the {@link BlockBreaker} port: begin on the
 * first tick, then observe until the arm reports done or dead. All the physicality (reach, break
 * time, the crack, drops, exhaustion) lives behind the port; this task owns only the intent.
 *
 * <p>Collection is not this task's job: the body's walk-over pickup (or a later sweep
 * step) gathers the drops.
 */
public final class BreakBlock implements PrimitiveTask {
    private final Pos target;
    private boolean begun;

    public BreakBlock(int x, int y, int z) {
        this.target = new Pos(x, y, z);
    }

    @Override
    public TaskStatus tick(BrainContext ctx) {
        BlockBreaker breaker = ctx.actuators().breaker();
        if (!begun) {
            begun = true;
            // A refused begin (air, unbreakable, out of reach) fails immediately — the parent
            // method re-resolves; walking closer first is GoTo's job, not a retry loop's.
            return breaker.begin(target) ? TaskStatus.RUNNING : TaskStatus.FAILED;
        }
        return switch (breaker.state()) {
            case BREAKING -> TaskStatus.RUNNING;
            case FINISHED -> TaskStatus.SUCCESS;
            // IDLE after a successful begin = stopped out from under us; FAILED = the arm's own
            // verdict (block changed, reach lost). Either way this attempt is over.
            case IDLE, FAILED -> TaskStatus.FAILED;
        };
    }

    @Override
    public void cancel(BrainContext ctx) {
        ctx.actuators().breaker().abort();
    }

    @Override
    public String describe() {
        return "break (" + target.x() + ", " + target.y() + ", " + target.z() + ")";
    }
}
