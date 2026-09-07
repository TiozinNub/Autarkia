package dev.luizloyola.autarkia.core.tree;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.knowledge.BlockKind;
import dev.luizloyola.anima.core.brain.knowledge.FakeProbe;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.FakeContext;
import dev.luizloyola.anima.core.brain.task.TaskStatus;
import dev.luizloyola.anima.core.log.Entry;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Step one of the eighth chop: look at the ground beside the stump, say so, keep looking. */
class FellTreeTest {

    private static final int BASE = FakeProbe.GROUND_Y + 1;
    private static final Pos ANCHOR = new Pos(0, BASE, 0);

    private final FakeContext ctx = new FakeContext();
    private final FellTree task = new FellTree(ANCHOR);

    private void trunk() {
        for (int y = BASE; y < BASE + 6; y++) {
            ctx.percepts.blocks.set(0, y, 0, BlockKind.LOG);
        }
    }

    private List<String> said() {
        return ctx.journalService.recent(ctx.journal().id(), Integer.MAX_VALUE).stream()
                .filter(entry -> entry.event().equals("chop"))
                .map(Entry::detail)
                .toList();
    }

    @Test
    void looksOnTheFirstTickAndKeepsRunning() {
        trunk();

        assertEquals(TaskStatus.RUNNING, task.tick(ctx));
        Approach approach = task.approach().orElseThrow();
        assertEquals(4, approach.sides().size());
        assertEquals(4, approach.approachable());
        for (int i = 0; i < 3 * FellTree.RESURVEY_TICKS; i++) {
            assertEquals(TaskStatus.RUNNING, task.tick(ctx), "step one never ends on its own");
        }
        assertTrue(task.describe().endsWith("N open (0) · E open (0) · S open (0) · W open (0)"),
                "the readout carries the verdicts: " + task.describe());
    }

    @Test
    void saysWhatItReadOnceUntilTheGroundChanges() {
        trunk();
        for (int i = 0; i < FellTree.RESURVEY_TICKS + 1; i++) {
            task.tick(ctx);
        }
        assertEquals(List.of("approach — N open (0) · E open (0) · S open (0) · W open (0)"), said(),
                "a re-read that says the same thing says nothing");

        ctx.percepts.blocks.set(1, BASE, 0, BlockKind.OTHER);
        for (int i = 0; i < FellTree.RESURVEY_TICKS; i++) {
            task.tick(ctx);
        }
        assertEquals(2, said().size());
        assertEquals("approach — N open (0) · E up 1 (2) · S open (0) · W open (0)", said().get(1));
    }

    @Test
    void cancelBeforeAndAfterLookingHoldsNothing() {
        task.cancel(ctx);
        trunk();
        task.tick(ctx);
        task.cancel(ctx);
        assertEquals(0, ctx.breaker.begins);
        assertEquals(0, ctx.mover.moveToCalls);
    }
}
