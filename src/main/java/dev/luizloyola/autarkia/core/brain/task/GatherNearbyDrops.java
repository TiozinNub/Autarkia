package dev.luizloyola.autarkia.core.brain.task;

import dev.luizloyola.autarkia.core.brain.BrainContext;
import dev.luizloyola.autarkia.core.brain.act.MoveState;
import dev.luizloyola.autarkia.core.brain.sense.Drop;
import dev.luizloyola.autarkia.core.brain.sense.Pos;
import dev.luizloyola.autarkia.core.inv.ItemSpec;
import java.util.ArrayList;
import java.util.List;

/**
 * Sweep every sighted drop matching a spec: walk the nearest flock centroid ({@link Flocks}), let
 * the walk-over pickup vacuum the path, re-scan, repeat until nothing matching is in sight. The
 * sight radius is the work area — no bounds parameter; scope the spec instead.
 *
 * <p>Outcome is measured in the pack: SUCCESS only when {@code count(spec)} rose since the first
 * tick. Empty-handed (drops gone before arrival, or the lap guard tripped) is FAILED, which is
 * what lets an {@code ObtainItem} burn its pickup method and move on to producing.
 */
public final class GatherNearbyDrops implements PrimitiveTask {
    private final ItemSpec spec;

    private int startCount = -1;
    private int laps;
    private int lapCap = -1;
    private boolean walkIssued;

    public GatherNearbyDrops(ItemSpec spec) {
        this.spec = spec;
    }

    @Override
    public TaskStatus tick(BrainContext ctx) {
        if (startCount < 0) {
            startCount = ctx.percepts().inventory().count(spec.matcher());
        }
        List<Pos> matching = new ArrayList<>();
        for (Drop drop : ctx.percepts().drops()) {
            if (spec.matches(drop.itemId())) {
                matching.add(drop.pos());
            }
        }
        if (lapCap < 0) {
            lapCap = Flocks.count(matching) * 3 + 6;
        }
        if (matching.isEmpty() || laps >= lapCap) {
            ctx.actuators().mover().stop();
            boolean gathered = ctx.percepts().inventory().count(spec.matcher()) > startCount;
            return gathered ? TaskStatus.SUCCESS : TaskStatus.FAILED;
        }
        if (walkIssued && ctx.actuators().mover().state() == MoveState.MOVING) {
            return TaskStatus.RUNNING;
        }
        Pos centroid = Flocks.nearestCentroid(matching, ctx.percepts().position());
        ctx.actuators().mover().moveTo(centroid.x(), centroid.y(), centroid.z());
        walkIssued = true;
        laps++;
        return TaskStatus.RUNNING;
    }

    @Override
    public void cancel(BrainContext ctx) {
        ctx.actuators().mover().stop();
    }

    @Override
    public String describe() {
        return "gather " + spec.name();
    }
}
