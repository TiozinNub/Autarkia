package dev.luizloyola.autarkia.mod.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.knowledge.Region;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.autarkia.core.board.ClearArea;
import dev.luizloyola.autarkia.core.board.PartyBoard;
import dev.luizloyola.autarkia.core.board.WorkKey;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A party's board writes itself down and comes back as it was, through {@code JsonOps} — no
 * server, no save file.
 *
 * <p>Vanilla parses saved data with {@code resultOrPartial}: a row that stopped decoding does not
 * fail the load, it comes back at the right version with fewer rows and the next autosave writes
 * that over the file. {@code StoreGuard} catches it at boot by counting; this, before shipping.
 */
class PartyBoardCodecsTest {

    private static PartyBoard.Row roundTrip(PartyBoard.Row row) {
        var encoded = PartyBoardCodecs.ROW.encodeStart(JsonOps.INSTANCE, row).getOrThrow();
        return PartyBoardCodecs.ROW.parse(JsonOps.INSTANCE, encoded).getOrThrow();
    }

    private static ClearArea.State state(ClearArea.Phase phase, List<ClearArea.Target> targets) {
        return new ClearArea.State("trees",
                new Region(new Pos(-10, 60, -20), new Pos(70, 90, 40)),
                0.5, phase, List.of(0, 2), List.of(new ClearArea.SliceCooldown(1, 12_345L)),
                targets);
    }

    @Test
    void aProjectComesBackWithItsBoxItsPhaseAndItsLedger() {
        ClearArea.State before = state(ClearArea.Phase.CLEARING, List.of(
                new ClearArea.Target(new Pos(3, 61, 4), ClearArea.TargetState.OPEN, 0, 0L),
                new ClearArea.Target(new Pos(9, 62, 9), ClearArea.TargetState.CLEARED, 0, 0L),
                new ClearArea.Target(new Pos(11, 63, 2), ClearArea.TargetState.REFUSED, 3, 0L)));

        PartyBoard.Row after = roundTrip(new PartyBoard.Row(before, List.of()));
        assertEquals(before, after.project());
    }

    @Test
    void aRefusalKeepsItsCountAcrossTheFile() {
        // The count is what decides whether a reloaded target gets three more tries or none.
        ClearArea.Target stubborn =
                new ClearArea.Target(new Pos(1, 2, 3), ClearArea.TargetState.OPEN, 2, 900L);
        PartyBoard.Row after = roundTrip(
                new PartyBoard.Row(state(ClearArea.Phase.CLEARING, List.of(stubborn)), List.of()));
        assertEquals(stubborn, after.project().targets().get(0));
    }

    @Test
    void everyHoldComesBackWithItsOwnerAndItsErrand() {
        AgentId alice = AgentId.random();
        AgentId bob = AgentId.random();
        List<PartyBoard.Hold> holds = List.of(
                new PartyBoard.Hold(new WorkKey(WorkKey.SURVEY, new Pos(0, 60, 48)), alice),
                new PartyBoard.Hold(new WorkKey(WorkKey.CLEAR, new Pos(3, 61, 4)), bob));

        PartyBoard.Row after =
                roundTrip(new PartyBoard.Row(state(ClearArea.Phase.SURVEYING, List.of()), holds));
        assertEquals(holds, after.holds());
    }

    @Test
    void aSurveysProgressSurvivesTheFile() {
        PartyBoard.Row after =
                roundTrip(new PartyBoard.Row(state(ClearArea.Phase.VERIFYING, List.of()), List.of()));
        assertEquals(List.of(0, 2), after.project().reported());
        assertEquals(1, after.project().sliceCooldowns().get(0).slice());
        assertEquals(12_345L, after.project().sliceCooldowns().get(0).retryAfter());
    }

    @Test
    void everyPhaseAndEveryTargetStateSurvivesARename() {
        // By NAME, not ordinal: reordering either enum must not silently move saved rows to a
        // different meaning. This fails the day somebody switches to ordinals for compactness.
        for (ClearArea.Phase phase : ClearArea.Phase.values()) {
            var encoded = PartyBoardCodecs.PHASE.encodeStart(JsonOps.INSTANCE, phase).getOrThrow();
            assertEquals("\"" + phase.name() + "\"", encoded.toString());
        }
        for (ClearArea.TargetState state : ClearArea.TargetState.values()) {
            var encoded =
                    PartyBoardCodecs.TARGET_STATE.encodeStart(JsonOps.INSTANCE, state).getOrThrow();
            assertEquals("\"" + state.name() + "\"", encoded.toString());
        }
    }

    @Test
    void aRowWrittenBeforeTheOptionalFieldsExistedStillReads() {
        // optionalFieldOf is what lets a file written by an older build load at all; if these
        // defaults are ever tightened to fieldOf, every pre-existing save stops decoding — and
        // decodes to NOTHING rather than to an error. That is the whole trap.
        JsonObject minimal = new JsonObject();
        minimal.addProperty("clearing", "trees");
        minimal.add("bounds", PartyBoardCodecs.REGION.encodeStart(JsonOps.INSTANCE,
                new Region(new Pos(0, 0, 0), new Pos(1, 1, 1))).getOrThrow());
        minimal.addProperty("priority", 0.5);
        minimal.addProperty("phase", "SURVEYING");
        ClearArea.State read = PartyBoardCodecs.PROJECT.parse(JsonOps.INSTANCE, minimal).getOrThrow();
        assertTrue(read.reported().isEmpty());
        assertTrue(read.targets().isEmpty());
        assertTrue(read.sliceCooldowns().isEmpty());
    }
}
