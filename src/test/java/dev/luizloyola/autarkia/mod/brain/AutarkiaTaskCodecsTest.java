package dev.luizloyola.autarkia.mod.brain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.JsonOps;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.Idle;
import dev.luizloyola.anima.core.brain.task.Task;
import dev.luizloyola.anima.mod.brain.AnimaTasks;
import dev.luizloyola.anima.arch.SourceTree;
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

    /** Where Autarkia's own tasks live — the layer the scan below reads. */
    private static final String CORE = "dev.luizloyola.autarkia.core";

    /**
     * The guard Autarkia never had, and the reason a server died on 2026-08-20.
     *
     * <p>Anima has carried {@code everyTaskAnimaDeclaresCanWriteItselfDown} since the social rungs;
     * this side had nothing, so {@code ChopPlannedTree} was registered by hand and stayed the only
     * one anybody remembered. A task written without a codec is invisible until a world autosaves a
     * body holding one — and then it is not a failed save, it is a dead server.
     */
    @Test
    void everyTaskAutarkiaDeclaresCanWriteItselfDown() {
        java.util.List<Class<?>> declared = new java.util.ArrayList<>();
        for (SourceTree.JavaSource file
                : SourceTree.fromSystemProperty("autarkia.arch.sourceRoot").inPackage(CORE)) {
            collectTasks(loaded(file.path()), declared);
        }
        // A scan that finds nothing is a guard that passes by looking away.
        assertTrue(declared.size() >= 2,
                "only " + declared.size() + " task types found: the scan has lost the source tree");

        java.util.List<String> orphans = declared.stream()
                .filter(type -> !TaskCodecs.types().contains(type))
                .map(Class::getSimpleName)
                .toList();
        assertTrue(orphans.isEmpty(), () -> SourceTree.report(
                "every Task Autarkia declares must be registered with TaskCodecs — a plan holding "
                        + "an unregistered one cannot be saved, and the save takes the server down",
                orphans));
    }

    /** The class a source file declares, or null for {@code package-info} and its kin. */
    private static Class<?> loaded(String path) {
        String binary = path.substring(0, path.length() - ".java".length()).replace('/', '.');
        try {
            // Not initialised: this only asks what a class IS.
            return Class.forName(binary, false, AutarkiaTaskCodecsTest.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }

    /** {@code type} and every class nested in it that a plan could hold. */
    private static void collectTasks(Class<?> type, java.util.List<Class<?>> found) {
        if (type == null) {
            return;
        }
        if (Task.class.isAssignableFrom(type) && !type.isInterface()
                && !java.lang.reflect.Modifier.isAbstract(type.getModifiers())) {
            found.add(type);
        }
        for (Class<?> nested : type.getDeclaredClasses()) {
            collectTasks(nested, found);
        }
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
