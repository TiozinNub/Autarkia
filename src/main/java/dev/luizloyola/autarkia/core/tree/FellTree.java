package dev.luizloyola.autarkia.core.tree;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.PrimitiveTask;
import dev.luizloyola.anima.core.brain.task.TaskStatus;
import dev.luizloyola.anima.core.log.Category;
import dev.luizloyola.anima.core.nav.MoveCapabilities;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The eighth chop, being built one step at a time (decision: Luiz, 2026-09-07). <b>Step one</b>:
 * stand where you are, read which sides of the stump could be walked up to ({@link Approach}),
 * say so, and keep reading. It moves nothing, breaks nothing, and never ends — RUNNING for as long
 * as it is left in the slot, so what it reads can be watched in-world ({@code /autarkia tree
 * approach}) and changed under it.
 *
 * <p>The seventh choreography — a compiled dance card and the 1600-line executor that walked it —
 * was deleted whole on 2026-09-06 for this redesign, the way the six before it went on
 * 2026-08-02. <b>Detection was not touched</b>: {@link TreeShape} still answers whose wood is whose.
 * The four callers that hand a tree to the axe — {@link ChopForLogs}, {@link TreeClearing}, the
 * chop wand and {@code /autarkia brain chop} — keep their shape and their tests.
 */
public final class FellTree implements PrimitiveTask {

    /**
     * How often the ground around the stump is re-read while the body stands and looks: every
     * second, so a block placed beside the tree shows up in the next readout, not the next order.
     */
    public static final int RESURVEY_TICKS = 20;

    private final Pos anchor;
    private @Nullable Approach approach;
    private int ticks;
    /** The last summary journalled — a re-read that says the same thing says nothing. */
    private String told = "";

    public FellTree(Pos anchor) {
        this.anchor = anchor;
    }

    public Pos anchor() {
        return anchor;
    }

    /** What the last look found, once there has been one — the debug view's whole input. */
    public Optional<Approach> approach() {
        return Optional.ofNullable(approach);
    }

    @Override
    public TaskStatus tick(BrainContext ctx) {
        if (approach == null || ticks % RESURVEY_TICKS == 0) {
            approach = Approach.survey(anchor, ctx.percepts().position(), ctx.percepts().blocks(),
                    MoveCapabilities.of(ctx.profile()).clearCells());
            String now = approach.summary();
            if (!now.equals(told)) {
                ctx.journal().record(Category.BRAIN, "chop", "approach — " + now);
                told = now;
            }
        }
        ticks++;
        return TaskStatus.RUNNING; // step one looks and keeps looking; nothing here ends it
    }

    @Override
    public void cancel(BrainContext ctx) {
        // Nothing ordered: no claim taken, no actuator held, no ground disturbed.
    }

    @Override
    public String describe() {
        return "fell the tree at " + where()
                + (approach == null ? "" : " — " + approach.summary());
    }

    @Override
    public String failureDetail() {
        return "nobody knows how to fell a tree yet";
    }

    /**
     * True already, and deliberately so: whatever the eighth turns out to be, it puts a body
     * somewhere precarious on purpose, and the escape drive must not preempt it. Declaring it here
     * keeps the exemption wired while the choreography is missing.
     */
    @Override
    public boolean reshapesGround() {
        return true;
    }

    private String where() {
        return "(" + anchor.x() + ", " + anchor.y() + ", " + anchor.z() + ")";
    }
}
