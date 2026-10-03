package dev.luizloyola.autarkia.core.board;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.WorkToleranceCurve;
import dev.luizloyola.anima.core.brain.sense.BeingId;
import dev.luizloyola.anima.core.brain.task.Follow;
import dev.luizloyola.anima.core.brain.task.Method;
import dev.luizloyola.anima.core.brain.task.ObtainItem;
import dev.luizloyola.anima.core.brain.task.PrimitiveTask;
import dev.luizloyola.anima.core.brain.task.TaskStatus;
import dev.luizloyola.anima.core.continuity.Ephemeral;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.core.store.Store;
import java.util.Set;

/**
 * An expedition's companion keeping with the one who leads it, until it knows a source of its own
 * within an ordinary budget — the far patch, once it has seen it on the way. Knowledge is never
 * shared, so walking there together is how a companion learns where to go.
 *
 * <p>Fails if the one followed is lost from sight, or once it has got no further from HOME for
 * {@link #STALL_TICKS}: a leader who went nowhere is not leading anybody.
 */
public final class TravelWith implements PrimitiveTask {

    /** Past the leader's muster ({@link Expedition#MUSTER_TICKS}) and any kitting up at HOME *(call)*. */
    public static final int STALL_TICKS = 1800;

    /** How often the source is looked for — it builds an obtain's whole roster. */
    static final int LOOK_EVERY = 20;

    private final BeingId leader;
    private final ItemSpec resource;
    private final Set<String> pursued;
    private int stalled;
    private double farthest;
    @Ephemeral("made again from the leader's id; it re-aims from what it sees")
    private Follow follow;
    @Ephemeral("a pacing count; a restart looks again at once")
    private int sinceLook;

    public TravelWith(BeingId leader, ItemSpec resource, Set<String> pursued) {
        this(leader, resource, pursued, 0, 0);
    }

    public TravelWith(BeingId leader, ItemSpec resource, Set<String> pursued, int stalled, double farthest) {
        this.leader = leader;
        this.resource = resource;
        this.pursued = Set.copyOf(pursued);
        this.stalled = stalled;
        this.farthest = farthest;
    }

    @Override
    public TaskStatus tick(BrainContext ctx) {
        if (sinceLook++ % LOOK_EVERY == 0 && withinReach(ctx)) {
            ctx.actuators().mover().stop();
            return TaskStatus.SUCCESS;
        }
        double out = ctx.depot().map(site -> Store.distance(site.hint(), ctx.percepts().position())).orElse(0.0);
        if (out > farthest + 1) {
            farthest = out;
            stalled = 0;
        } else if (++stalled >= STALL_TICKS) {
            return TaskStatus.FAILED;
        }
        if (follow == null) {
            follow = new Follow(leader, null);
        }
        TaskStatus status = follow.tick(ctx);
        if (status == TaskStatus.SUCCESS) {
            follow = null; // together and standing: keep with them, they have not got there yet
        }
        return status == TaskStatus.FAILED ? TaskStatus.FAILED : TaskStatus.RUNNING;
    }

    /** Whether a way to the resource is known that an ordinary errand could afford from here. */
    private boolean withinReach(BrainContext ctx) {
        ObtainItem obtain = new ObtainItem(resource, 1, pursued, ObtainItem.Sources.NOT_STORES);
        for (Method way : obtain.methods()) {
            if (way.applicable(ctx) && way.estimateCost(ctx) <= WorkToleranceCurve.CAP) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void cancel(BrainContext ctx) {
        if (follow != null) {
            follow.cancel(ctx);
        }
    }

    @Override
    public String failureDetail() {
        return stalled >= STALL_TICKS ? "keep with the leader: they went nowhere" : "keep with the leader: lost them";
    }

    @Override
    public String describe() {
        return "keep with the one leading the way";
    }

    public BeingId leader() {
        return leader;
    }

    public ItemSpec resource() {
        return resource;
    }

    public Set<String> pursued() {
        return pursued;
    }

    public int stalled() {
        return stalled;
    }

    public double farthest() {
        return farthest;
    }
}
