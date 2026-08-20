package dev.luizloyola.autarkia.mod.brain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.mojang.serialization.JsonOps;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.Idle;
import dev.luizloyola.anima.core.brain.task.Task;
import dev.luizloyola.anima.mod.brain.AnimaTasks;
import dev.luizloyola.anima.mod.brain.TaskCodecs;
import dev.luizloyola.autarkia.core.board.HaulingErrand;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Every task Autarkia can leave in a saved plan must have a codec.
 *
 * <p><b>This is not a formality.</b> An unregistered task does not fail loudly on save: {@code
 * keyOf} answers null, Mojang's dispatch codec writes that null into a string field, and the world
 * dies with a {@code NullPointerException} out of a JSON primitive — taking the server with it. It
 * happened on 2026-08-20, between a settler felling a tree and the next autosave, because
 * {@link HaulingErrand} was written without one.
 */
class AutarkiaTaskCodecsTest {

    @BeforeAll
    static void registerTheTypes() {
        AnimaTasks.install();
        AutarkiaTasks.install();
    }

    private static Task roundTrip(Task task) {
        var encoded = TaskCodecs.codec().encodeStart(JsonOps.INSTANCE, task).getOrThrow();
        return TaskCodecs.codec().parse(JsonOps.INSTANCE, encoded).getOrThrow();
    }

    @Test
    void aHaulingErrandSurvivesTheFile() {
        HaulingErrand before = new HaulingErrand(new Idle(7), new Pos(10, 64, 10), 3);

        HaulingErrand after = assertInstanceOf(HaulingErrand.class, roundTrip(before));

        assertEquals(before.yard(), after.yard());
        assertEquals(before.haulLine(), after.haulLine());
        assertInstanceOf(Idle.class, after.work(), "and it carries its work with it");
    }
}
