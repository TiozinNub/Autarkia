package dev.luizloyola.autarkia.core.builder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.luizloyola.anima.core.brain.act.MoveState;
import dev.luizloyola.anima.core.brain.act.Placing;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.FakeContext;
import dev.luizloyola.anima.core.brain.task.TaskExecutor;
import dev.luizloyola.anima.core.brain.task.TaskStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class LayPieceTest {

    private static final String PLANKS = "minecraft:oak_planks";

    /** Steps far enough apart that each is walked to from its own stand. */
    private static Laying step(int x) {
        return new Laying(Section.WALLS, new Placing(PLANKS, new Pos(x, 64, 0), PLANKS, Map.of()), List.of(),
                new Pos(x, 64, 2), 1);
    }

    @Test
    void theFirstStepThatWillNotGoInEndsThePiece() {
        FakeContext ctx = new FakeContext();
        ctx.percepts.position = new Pos(0, 64, 30);
        LayPiece piece = new LayPiece(List.of(step(0), step(10), step(20), step(30)));
        TaskExecutor executor = new TaskExecutor();
        executor.run(piece, ctx);
        List<Pos> walkedTo = new ArrayList<>();
        int walks = 0;
        for (int tick = 0; tick < 600 && executor.isBusy(); tick++) {
            ctx.percepts.time++;
            if (ctx.mover.moveToCalls > walks) {
                walks = ctx.mover.moveToCalls;
                ctx.percepts.position = new Pos(ctx.mover.lastX, ctx.mover.lastY, ctx.mover.lastZ);
                walkedTo.add(ctx.percepts.position);
                ctx.mover.setState(MoveState.ARRIVED);
            }
            // The first goes in; from then on the planks have run out.
            ctx.placer.refuse = !ctx.placer.placed.isEmpty();
            executor.tick(ctx);
        }

        assertEquals(Optional.of(TaskStatus.FAILED), executor.lastStatus(), "a piece that stopped short is no success");
        assertEquals(1, ctx.placer.placed.size());
        assertEquals(List.of(new Pos(0, 64, 2), new Pos(10, 64, 2)), walkedTo, "nothing past the refused step is tried");
        assertFalse(walkedTo.contains(new Pos(20, 64, 2)));
        assertEquals(1, ctx.journalService.recent(ctx.journal().id(), Integer.MAX_VALUE).stream()
                .filter(e -> e.detail().startsWith("gave up on ")).count());
    }
}
