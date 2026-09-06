package dev.luizloyola.autarkia.core.tree;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.PrimitiveTask;
import dev.luizloyola.anima.core.brain.task.TaskStatus;
import dev.luizloyola.anima.core.log.Category;

/**
 * The hole where the eighth chop goes: it fells nothing, and says so on its first tick.
 *
 * <p>The seventh choreography — a compiled dance card and the 1600-line executor that walked it —
 * was deleted whole on 2026-09-06 for a from-scratch redesign (decision: Luiz), the way the six
 * before it went on 2026-08-02. <b>Detection was not touched</b>: {@link TreeShape} still answers
 * whose wood is whose, and {@code /autarkia tree view} still paints it.
 *
 * <p>Failing immediately is the point. The four callers that used to hand a tree to the axe —
 * {@link ChopForLogs}, {@link TreeClearing}, the chop wand and {@code /autarkia brain chop} —
 * keep their shape and their tests, so the redesign replaces a body instead of re-wiring them,
 * and nothing in between pretends to work.
 */
public final class FellTree implements PrimitiveTask {

    private final Pos anchor;

    public FellTree(Pos anchor) {
        this.anchor = anchor;
    }

    public Pos anchor() {
        return anchor;
    }

    @Override
    public TaskStatus tick(BrainContext ctx) {
        ctx.journal().record(Category.BRAIN, "chop", "no choreography — " + where());
        return TaskStatus.FAILED;
    }

    @Override
    public void cancel(BrainContext ctx) {
        // Nothing was ever ordered: no claim taken, no actuator held, no ground disturbed.
    }

    @Override
    public String describe() {
        return "fell the tree at " + where();
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
