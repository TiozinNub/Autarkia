package dev.luizloyola.autarkia.mod.brain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.autarkia.core.tree.FellTree;

import com.mojang.serialization.JsonOps;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.Idle;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.core.brain.task.Task;
import dev.luizloyola.anima.mod.brain.AnimaTasks;
import dev.luizloyola.anima.arch.SourceTree;
import dev.luizloyola.anima.mod.brain.TaskCodecs;
import dev.luizloyola.autarkia.core.board.GatheringErrand;
import dev.luizloyola.autarkia.core.board.HaulingErrand;
import dev.luizloyola.autarkia.core.board.Stock;
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
     * this side had nothing, so the chop was registered by hand and stayed the only one anybody
     * remembered. A task written without a codec is invisible until a world autosaves a
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

    @Test
    void aGatheringErrandSurvivesTheFile() {
        // A declared spec travels as its registry NAME: its matcher is a lambda, and a trip
        // reloaded against the wrong one would fetch nothing and never satisfy.
        GatheringErrand before = new GatheringErrand(Stock.LOGS, 16, new Pos(10, 64, 10));

        GatheringErrand after = assertInstanceOf(GatheringErrand.class, roundTrip(before));

        assertEquals(Stock.LOGS, after.spec());
        assertEquals(16, after.count());
        assertEquals(before.yard(), after.yard());
    }

    @Test
    void aTripForAnItemNobodyDeclaredSurvivesTheFile() {
        // `board post gather <item>` posts an ItemSpec.anyOf, so the member walking it is holding
        // a spec no bootstrap re-registers. By NAME that plan cannot decode at all, and an
        // undecodable task in a saved plan is the failure this class exists for.
        ItemSpec literal = ItemSpec.anyOf(java.util.Set.of("minecraft:oak_log"));
        GatheringErrand before = new GatheringErrand(literal, 16, new Pos(10, 64, 10));

        var written = TaskCodecs.codec().encodeStart(JsonOps.INSTANCE, before).getOrThrow();
        assertTrue(written.getAsJsonObject().get("spec").isJsonArray(),
                "the ids, not \"" + literal.name() + "\" — nothing declares that name at boot");

        GatheringErrand after = assertInstanceOf(GatheringErrand.class, roundTrip(before));
        assertTrue(after.spec().matches("minecraft:oak_log"));
    }

    /** The chop mid-climb: its stage, the side it took and its plan come back exactly. */
    @Test
    void aFellTreeMidClimbComesBackWhereItWas() {
        var anchor = new dev.luizloyola.anima.core.brain.sense.Pos(208, -60, 0);
        var stand = new dev.luizloyola.anima.core.brain.sense.Pos(208, -59, 0);
        var climb = new dev.luizloyola.autarkia.core.tree.Climb(stand, -57, true, false,
                java.util.List.of(new dev.luizloyola.anima.core.brain.sense.Pos(208, -59, 0)),
                java.util.List.of(new dev.luizloyola.anima.core.brain.sense.Pos(208, -57, 0)),
                java.util.List.of(anchor),
                java.util.List.of(),
                false,
                java.util.List.of(anchor),
                true,
                java.util.List.of(new dev.luizloyola.anima.core.brain.sense.Pos(210, -57, 0)),
                java.util.List.of(new dev.luizloyola.autarkia.core.tree.Climb.Lean(
                        new dev.luizloyola.anima.core.brain.sense.Pos(212, -56, 0), stand)));
        var chosen = new dev.luizloyola.anima.core.brain.sense.Pos(207, -60, 0);
        var task = FellTree.restored(anchor, FellTree.Stage.OPEN,
                java.util.Optional.of(chosen), java.util.Optional.of(climb));

        var back = (FellTree) roundTrip(task);
        assertEquals(anchor, back.anchor());
        assertEquals(FellTree.Stage.OPEN, back.stage());
        assertEquals(chosen, back.chosen().orElseThrow());
        assertEquals(climb, back.climb().orElseThrow());
    }

    /** A save from before the chop had stages reads as a fresh approach, not a dead server. */
    @Test
    void anAnchorOnlyFellTreeStillReads() {
        var back = (FellTree) roundTrip(new FellTree(
                new dev.luizloyola.anima.core.brain.sense.Pos(1, 2, 3)));
        assertEquals(FellTree.Stage.APPROACH, back.stage());
        assertTrue(back.climb().isEmpty());
    }
}
