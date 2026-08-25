package dev.luizloyola.autarkia.mod.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.knowledge.CoverageGrid;
import dev.luizloyola.anima.core.brain.knowledge.Region;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.autarkia.core.board.ClearArea;
import dev.luizloyola.autarkia.core.board.PartyBoard;
import dev.luizloyola.autarkia.core.board.ProjectState;
import dev.luizloyola.autarkia.core.board.WorkKey;
import java.util.List;
import net.minecraft.core.UUIDUtil;
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
                0.5, phase, List.of(new ClearArea.SliceCooldown(1, 12_345L)),
                targets, 7,
                List.of(new ClearArea.CellMask(new Pos(0, 60, 0), CoverageGrid.FULL),
                        new ClearArea.CellMask(new Pos(8, 60, 0), 0x00FF)),
                new Pos(40, 63, 40), List.of(new Pos(41, 63, 40)));
    }

    @Test
    void aRowNamesTheKindOfProjectItHolds() {
        PartyBoard.Row row = new PartyBoard.Row(state(ClearArea.Phase.WORKING, List.of()), List.of());
        var encoded = PartyBoardCodecs.ROW.encodeStart(JsonOps.INSTANCE, row).getOrThrow();

        assertEquals("clear_area",
                encoded.getAsJsonObject().getAsJsonObject("project").get("type").getAsString(),
                "a row that does not name its kind cannot be read back once a second kind exists");
        assertEquals(row, PartyBoardCodecs.ROW.parse(JsonOps.INSTANCE, encoded).getOrThrow());
    }

    @Test
    void aProjectComesBackWithItsBoxItsPhaseAndItsLedger() {
        ClearArea.State before = state(ClearArea.Phase.WORKING, List.of(
                new ClearArea.Target(new Pos(3, 61, 4), ClearArea.TargetState.OPEN, 0, 0L, List.of()),
                new ClearArea.Target(new Pos(9, 62, 9), ClearArea.TargetState.CLEARED, 0, 0L, List.of()),
                new ClearArea.Target(new Pos(11, 63, 2), ClearArea.TargetState.REFUSED, 3, 0L,
                        List.of(AgentId.random(), AgentId.random(), AgentId.random()))));

        PartyBoard.Row after = roundTrip(new PartyBoard.Row(before, List.of()));
        assertEquals(before, after.project());
    }

    @Test
    void aBoxWithNoYardRoundTripsWithoutOne() {
        ClearArea.State plain = new ClearArea.State("trees",
                new Region(new Pos(-10, 60, -20), new Pos(70, 90, 40)),
                0.5, ClearArea.Phase.WORKING, List.of(), List.of(), 0, List.of(), null, List.of());

        PartyBoard.Row after = roundTrip(new PartyBoard.Row(plain, List.of()));

        assertEquals(plain, after.project());
        assertNull(((ClearArea.State) after.project()).yard(),
                "no destination, no migration, no surprise chest");
    }

    @Test
    void aRefusalKeepsItsCountAcrossTheFile() {
        // The count is what decides whether a reloaded target gets three more tries or none.
        ClearArea.Target stubborn =
                new ClearArea.Target(new Pos(1, 2, 3), ClearArea.TargetState.OPEN, 2, 900L,
                        List.of(AgentId.random(), AgentId.random()));
        PartyBoard.Row after = roundTrip(
                new PartyBoard.Row(state(ClearArea.Phase.WORKING, List.of(stubborn)), List.of()));
        assertEquals(stubborn, ((ClearArea.State) after.project()).targets().get(0));
    }

    @Test
    void everyHoldComesBackWithItsOwnerAndItsErrand() {
        AgentId alice = AgentId.random();
        AgentId bob = AgentId.random();
        List<PartyBoard.Hold> holds = List.of(
                new PartyBoard.Hold(new WorkKey.AtPlace(WorkKey.SURVEY, new Pos(0, 60, 48)), alice),
                new PartyBoard.Hold(new WorkKey.AtPlace(WorkKey.CLEAR, new Pos(3, 61, 4)), bob));

        PartyBoard.Row after =
                roundTrip(new PartyBoard.Row(state(ClearArea.Phase.WORKING, List.of()), holds));
        assertEquals(holds, after.holds());
    }

    @Test
    void theFrontierSurvivesTheFile() {
        // The masks, not just which cells were touched: a partial cell read back as a full one is
        // a box that closes over ground nobody covered, which is the one failure this must not have.
        PartyBoard.Row after =
                roundTrip(new PartyBoard.Row(state(ClearArea.Phase.WORKING, List.of()), List.of()));
        ClearArea.State project = (ClearArea.State) after.project();
        assertEquals(List.of(new ClearArea.CellMask(new Pos(0, 60, 0), CoverageGrid.FULL),
                new ClearArea.CellMask(new Pos(8, 60, 0), 0x00FF)), project.covered());
        assertEquals(1, project.sliceCooldowns().get(0).slice());
        assertEquals(12_345L, project.sliceCooldowns().get(0).retryAfter());
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
    void theLicenceToReopenRefusalsSurvivesTheFile() {
        // A reload that forgot it would either strand refusals that had earned another go, or hand
        // out a retry no felling had paid for.
        PartyBoard.Row after =
                roundTrip(new PartyBoard.Row(state(ClearArea.Phase.WORKING, List.of()), List.of()));
        assertEquals(7, ((ClearArea.State) after.project()).felledSinceReopen());
    }

    @Test
    void whoFailedATargetSurvivesTheFile() {
        // Refusing means several DIFFERENT people could not do it, so the set of who tried is the
        // evidence. A reload that dropped it would hand a stuck worker a fresh chance to condemn
        // the same tree over and over.
        AgentId alice = AgentId.random();
        AgentId bob = AgentId.random();
        ClearArea.Target tried = new ClearArea.Target(new Pos(4, 5, 6),
                ClearArea.TargetState.OPEN, 2, 0L, List.of(alice, bob));
        PartyBoard.Row after = roundTrip(
                new PartyBoard.Row(state(ClearArea.Phase.WORKING, List.of(tried)), List.of()));
        assertEquals(List.of(alice, bob),
                ((ClearArea.State) after.project()).targets().get(0).failedBy());
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
        minimal.addProperty("phase", "WORKING");
        // CLEAR_AREA, not the dispatching PROJECT: this is testing clear_area's OWN field defaults,
        // a level below which kind of row it is.
        ClearArea.State read =
                PartyBoardCodecs.CLEAR_AREA.codec().parse(JsonOps.INSTANCE, minimal).getOrThrow();
        assertTrue(read.covered().isEmpty());
        assertTrue(read.targets().isEmpty());
        assertTrue(read.sliceCooldowns().isEmpty());
    }

    @Test
    void aRowWrittenBeforeTheFrontierLoadsItsSweptGroundAsCoveredCells() {
        // Pre-2026-08-23 saves named whole settled corners and a pass that is no longer a state.
        // Dropping either puts a settler back on ground the party had already walked, and a short
        // read presents as a clearing that finished early — which is what StoreGuard's count is for.
        JsonObject legacy = new JsonObject();
        legacy.addProperty("clearing", "trees");
        legacy.add("bounds", PartyBoardCodecs.REGION.encodeStart(JsonOps.INSTANCE,
                new Region(new Pos(0, 60, 0), new Pos(31, 70, 31))).getOrThrow());
        legacy.addProperty("priority", 0.5);
        legacy.addProperty("phase", "VERIFYING");
        legacy.add("swept", PartyBoardCodecs.POS.listOf().encodeStart(JsonOps.INSTANCE,
                List.of(new Pos(0, 60, 0), new Pos(8, 60, 8))).getOrThrow());

        ClearArea.State read =
                PartyBoardCodecs.CLEAR_AREA.codec().parse(JsonOps.INSTANCE, legacy).getOrThrow();

        assertEquals(ClearArea.Phase.WORKING, read.phase(), "a pass name is not a state any more");
        assertEquals(2, read.covered().size(), "a short read here is a box re-swept from scratch");
        assertTrue(read.covered().stream().allMatch(cell -> cell.mask() == CoverageGrid.FULL));
    }

    @Test
    void aRowWrittenBeforeTypesExistedStillReadsAsClearArea() {
        // Absent "type" must default exactly as absent "kind" does for WorkKey: every row saved
        // before a second project kind existed lacks the field, and it was always a clearing.
        JsonObject legacy = new JsonObject();
        legacy.addProperty("clearing", "trees");
        legacy.add("bounds", PartyBoardCodecs.REGION.encodeStart(JsonOps.INSTANCE,
                new Region(new Pos(0, 0, 0), new Pos(1, 1, 1))).getOrThrow());
        legacy.addProperty("priority", 0.5);
        legacy.addProperty("phase", "WORKING");

        ProjectState read = PartyBoardCodecs.PROJECT.parse(JsonOps.INSTANCE, legacy).getOrThrow();

        assertEquals("clear_area", read.type());
        assertTrue(read instanceof ClearArea.State, "no type field predates a second kind existing");
    }

    @Test
    void aHoldWrittenBeforeKeysCouldNameAMemberStillReads() {
        AgentId alice = AgentId.random();
        JsonObject key = new JsonObject();
        key.addProperty("flavour", WorkKey.SURVEY);
        key.add("at", PartyBoardCodecs.POS.encodeStart(JsonOps.INSTANCE,
                new Pos(0, 60, 48)).getOrThrow());
        JsonObject hold = new JsonObject();
        hold.add("item", key);
        hold.add("who", UUIDUtil.CODEC.encodeStart(JsonOps.INSTANCE, alice.value()).getOrThrow());

        PartyBoard.Hold read = PartyBoardCodecs.HOLD.parse(JsonOps.INSTANCE, hold).getOrThrow();

        assertEquals(new WorkKey.AtPlace(WorkKey.SURVEY, new Pos(0, 60, 48)), read.key(),
                "a hold saved before this change is a place key — losing it costs a settler the "
                        + "errand they were walking to, and StoreGuard counts rows, not holds");
    }

    @Test
    void aMemberKeyedHoldRoundTrips() {
        AgentId alice = AgentId.random();
        PartyBoard.Hold hold = new PartyBoard.Hold(
                new WorkKey.ForMember(WorkKey.GATHER, alice), alice);
        var encoded = PartyBoardCodecs.HOLD.encodeStart(JsonOps.INSTANCE, hold).getOrThrow();
        assertEquals(hold, PartyBoardCodecs.HOLD.parse(JsonOps.INSTANCE, encoded).getOrThrow());
    }
}
