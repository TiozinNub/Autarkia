package dev.luizloyola.autarkia.mod.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.knowledge.CoverageGrid;
import dev.luizloyola.anima.core.brain.knowledge.Region;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.core.social.PartyId;
import dev.luizloyola.autarkia.core.board.ClearPlants;
import dev.luizloyola.autarkia.core.board.FellTrees;
import dev.luizloyola.autarkia.core.board.Gather;
import dev.luizloyola.autarkia.core.board.PartyBoard;
import dev.luizloyola.autarkia.core.board.SetUp;
import dev.luizloyola.autarkia.core.board.ProjectState;
import dev.luizloyola.autarkia.core.board.Stock;
import dev.luizloyola.autarkia.core.board.WorkKey;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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

    /** A member who worked the box, so the end of the job knows whose load to bring in. */
    private static final AgentId CREW = AgentId.random();

    private static PartyBoard.Row roundTrip(PartyBoard.Row row) {
        var encoded = PartyBoardCodecs.ROW.encodeStart(JsonOps.INSTANCE, row).getOrThrow();
        return PartyBoardCodecs.ROW.parse(JsonOps.INSTANCE, encoded).getOrThrow();
    }

    private static FellTrees.State state(FellTrees.Phase phase, List<FellTrees.Target> targets) {
        return new FellTrees.State("trees",
                new Region(new Pos(-10, 60, -20), new Pos(70, 90, 40)),
                0.5, phase, List.of(new FellTrees.SliceCooldown(1, 12_345L)),
                targets, 7,
                List.of(new FellTrees.CellMask(new Pos(0, 60, 0), CoverageGrid.FULL),
                        new FellTrees.CellMask(new Pos(8, 60, 0), 0x00FF)),
                true, List.of(CREW));
    }

    @Test
    void anAskToSiteABuildingComesBackAsItWasAsked() {
        var ask = new dev.luizloyola.autarkia.core.board.SiteBuilding.State("autarkia:basic_wooden_house",
                java.util.Map.of("base", "lv1", "beds", "none"), 0.5, false);

        assertEquals(ask, roundTrip(new PartyBoard.Row(ask, List.of())).project());
        var sited = new dev.luizloyola.autarkia.core.board.SiteBuilding.State(ask.blueprint(), ask.variants(), 0.5, true);
        assertEquals(sited, roundTrip(new PartyBoard.Row(sited, List.of())).project());
    }

    @Test
    void aSearchForAHomeComesBackWithItsFailedSettleWalks() {
        var plot = new dev.luizloyola.autarkia.core.direction.HomeJudge.Candidate(10, 0, 63, 17, 90,
                List.of(new dev.luizloyola.autarkia.core.direction.HomeJudge.Line(
                        dev.luizloyola.autarkia.core.direction.HomeJudge.Want.WATER, 4, 40)));
        var search = new dev.luizloyola.autarkia.core.direction.HomeSearch.State(new Pos(0, 64, 0),
                List.of(new Pos(0, 64, 0)), 1, 0, 2, List.of(), plot, null,
                dev.luizloyola.autarkia.core.direction.HomeSearch.Phase.SETTLE, List.of(), 2,
                List.of(plot));
        var state = new dev.luizloyola.autarkia.core.board.Explore.State(PartyId.random(), 0.5, search,
                java.util.Optional.of(CREW), List.of(), 100, List.of(), List.of(), true, 90);

        assertEquals(state, roundTrip(new PartyBoard.Row(state, List.of())).project());
    }

    @Test
    void aDeconstructionComesBackWithItsTally() {
        var targets = List.of(
                new dev.luizloyola.autarkia.core.board.Deconstruct.Target(new Pos(4, 64, 4), "minecraft:chest", true),
                new dev.luizloyola.autarkia.core.board.Deconstruct.Target(new Pos(6, 64, 4), "minecraft:crafting_table",
                        false));
        var state = new dev.luizloyola.autarkia.core.board.Deconstruct.State(targets, "moved in", 0.5,
                java.util.Map.of(new Pos(4, 64, 4), 3), java.util.Map.of(new Pos(6, 64, 4), true));

        assertEquals(state, roundTrip(new PartyBoard.Row(state, List.of())).project());
    }

    @Test
    void aRowNamesTheKindOfProjectItHolds() {
        PartyBoard.Row row = new PartyBoard.Row(state(FellTrees.Phase.WORKING, List.of()), List.of());
        var encoded = PartyBoardCodecs.ROW.encodeStart(JsonOps.INSTANCE, row).getOrThrow();

        assertEquals("fell_trees",
                encoded.getAsJsonObject().getAsJsonObject("project").get("type").getAsString(),
                "a row that does not name its kind cannot be read back once a second kind exists");
        assertEquals(row, PartyBoardCodecs.ROW.parse(JsonOps.INSTANCE, encoded).getOrThrow());
    }

    @Test
    void aProjectComesBackWithItsBoxItsPhaseAndItsLedger() {
        FellTrees.State before = state(FellTrees.Phase.WORKING, List.of(
                new FellTrees.Target(new Pos(3, 61, 4), FellTrees.TargetState.OPEN, 0, 0L, List.of()),
                new FellTrees.Target(new Pos(9, 62, 9), FellTrees.TargetState.CLEARED, 0, 0L, List.of()),
                new FellTrees.Target(new Pos(11, 63, 2), FellTrees.TargetState.REFUSED, 3, 0L,
                        List.of(AgentId.random(), AgentId.random(), AgentId.random()))));

        PartyBoard.Row after = roundTrip(new PartyBoard.Row(before, List.of()));
        assertEquals(before, after.project());
    }

    @Test
    void aBoxWithNoHaulRoundTripsWithoutOne() {
        FellTrees.State plain = new FellTrees.State("trees",
                new Region(new Pos(-10, 60, -20), new Pos(70, 90, 40)),
                0.5, FellTrees.Phase.WORKING, List.of(), List.of(), 0, List.of(), false, List.of());

        PartyBoard.Row after = roundTrip(new PartyBoard.Row(plain, List.of()));

        assertEquals(plain, after.project());
        assertFalse(((FellTrees.State) after.project()).home(),
                "no haul, no migration, no surprise chest");
    }

    @Test
    void aBoxSavedWithAYardStillTakesItsWoodHome() {
        FellTrees.State plain = new FellTrees.State("trees",
                new Region(new Pos(-10, 60, -20), new Pos(70, 90, 40)),
                0.5, FellTrees.Phase.WORKING, List.of(), List.of(), 0, List.of(), false, List.of());
        JsonObject row = PartyBoardCodecs.ROW.encodeStart(JsonOps.INSTANCE, new PartyBoard.Row(plain, List.of()))
                .getOrThrow().getAsJsonObject();
        JsonObject project = row.getAsJsonObject("project");
        project.remove("home");
        project.add("yard", PartyBoardCodecs.POS.encodeStart(JsonOps.INSTANCE, new Pos(0, 64, 0)).getOrThrow());

        FellTrees.State read = (FellTrees.State) PartyBoardCodecs.ROW.parse(JsonOps.INSTANCE, row).getOrThrow()
                .project();

        assertTrue(read.home(), "a box posted with a yard before 2026-10-01 hauled, and still does");
    }

    @Test
    void aRefusalKeepsItsCountAcrossTheFile() {
        // The count is what decides whether a reloaded target gets three more tries or none.
        FellTrees.Target stubborn =
                new FellTrees.Target(new Pos(1, 2, 3), FellTrees.TargetState.OPEN, 2, 900L,
                        List.of(AgentId.random(), AgentId.random()));
        PartyBoard.Row after = roundTrip(
                new PartyBoard.Row(state(FellTrees.Phase.WORKING, List.of(stubborn)), List.of()));
        assertEquals(stubborn, ((FellTrees.State) after.project()).targets().get(0));
    }

    @Test
    void everyHoldComesBackWithItsOwnerAndItsErrand() {
        AgentId alice = AgentId.random();
        AgentId bob = AgentId.random();
        List<PartyBoard.Hold> holds = List.of(
                new PartyBoard.Hold(new WorkKey.AtPlace(WorkKey.SURVEY, new Pos(0, 60, 48)), alice),
                new PartyBoard.Hold(new WorkKey.AtPlace(WorkKey.CLEAR, new Pos(3, 61, 4)), bob));

        PartyBoard.Row after =
                roundTrip(new PartyBoard.Row(state(FellTrees.Phase.WORKING, List.of()), holds));
        assertEquals(holds, after.holds());
    }

    @Test
    void theFrontierSurvivesTheFile() {
        // The masks, not just which cells were touched: a partial cell read back as a full one is
        // a box that closes over ground nobody covered, which is the one failure this must not have.
        PartyBoard.Row after =
                roundTrip(new PartyBoard.Row(state(FellTrees.Phase.WORKING, List.of()), List.of()));
        FellTrees.State project = (FellTrees.State) after.project();
        assertEquals(List.of(new FellTrees.CellMask(new Pos(0, 60, 0), CoverageGrid.FULL),
                new FellTrees.CellMask(new Pos(8, 60, 0), 0x00FF)), project.covered());
        assertEquals(1, project.sliceCooldowns().get(0).slice());
        assertEquals(12_345L, project.sliceCooldowns().get(0).retryAfter());
    }

    @Test
    void everyPhaseAndEveryTargetStateSurvivesARename() {
        // By NAME, not ordinal: reordering either enum must not silently move saved rows to a
        // different meaning. This fails the day somebody switches to ordinals for compactness.
        for (FellTrees.Phase phase : FellTrees.Phase.values()) {
            var encoded = PartyBoardCodecs.PHASE.encodeStart(JsonOps.INSTANCE, phase).getOrThrow();
            assertEquals("\"" + phase.name() + "\"", encoded.toString());
        }
        for (FellTrees.TargetState state : FellTrees.TargetState.values()) {
            var encoded =
                    PartyBoardCodecs.TARGET_STATE.encodeStart(JsonOps.INSTANCE, state).getOrThrow();
            assertEquals("\"" + state.name() + "\"", encoded.toString());
        }
    }

    @Test
    void theCrewSurvivesTheFile() {
        PartyBoard.Row after = roundTrip(
                new PartyBoard.Row(state(FellTrees.Phase.WORKING, List.of()), List.of()));

        assertEquals(List.of(CREW), ((FellTrees.State) after.project()).crew());
    }

    @Test
    void theLicenceToReopenRefusalsSurvivesTheFile() {
        // A reload that forgot it would either strand refusals that had earned another go, or hand
        // out a retry no felling had paid for.
        PartyBoard.Row after =
                roundTrip(new PartyBoard.Row(state(FellTrees.Phase.WORKING, List.of()), List.of()));
        assertEquals(7, ((FellTrees.State) after.project()).felledSinceReopen());
    }

    @Test
    void whoFailedATargetSurvivesTheFile() {
        // Refusing means several DIFFERENT people could not do it, so the set of who tried is the
        // evidence. A reload that dropped it would hand a stuck worker a fresh chance to condemn
        // the same tree over and over.
        AgentId alice = AgentId.random();
        AgentId bob = AgentId.random();
        FellTrees.Target tried = new FellTrees.Target(new Pos(4, 5, 6),
                FellTrees.TargetState.OPEN, 2, 0L, List.of(alice, bob));
        PartyBoard.Row after = roundTrip(
                new PartyBoard.Row(state(FellTrees.Phase.WORKING, List.of(tried)), List.of()));
        assertEquals(List.of(alice, bob),
                ((FellTrees.State) after.project()).targets().get(0).failedBy());
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
        // FELL_TREES, not the dispatching PROJECT: this is testing fell_trees's OWN field defaults,
        // a level below which kind of row it is.
        FellTrees.State read =
                PartyBoardCodecs.FELL_TREES.codec().parse(JsonOps.INSTANCE, minimal).getOrThrow();
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

        FellTrees.State read =
                PartyBoardCodecs.FELL_TREES.codec().parse(JsonOps.INSTANCE, legacy).getOrThrow();

        assertEquals(FellTrees.Phase.WORKING, read.phase(), "a pass name is not a state any more");
        assertEquals(2, read.covered().size(), "a short read here is a box re-swept from scratch");
        assertTrue(read.covered().stream().allMatch(cell -> cell.mask() == CoverageGrid.FULL));
    }

    @Test
    void aRowWrittenBeforeTypesExistedStillReadsAsFellTrees() {
        // Absent "type" must default exactly as absent "kind" does for WorkKey: every row saved
        // before a second project kind existed lacks the field, and it was always a clearing.
        JsonObject legacy = new JsonObject();
        legacy.addProperty("clearing", "trees");
        legacy.add("bounds", PartyBoardCodecs.REGION.encodeStart(JsonOps.INSTANCE,
                new Region(new Pos(0, 0, 0), new Pos(1, 1, 1))).getOrThrow());
        legacy.addProperty("priority", 0.5);
        legacy.addProperty("phase", "WORKING");

        ProjectState read = PartyBoardCodecs.PROJECT.parse(JsonOps.INSTANCE, legacy).getOrThrow();

        assertEquals("fell_trees", read.type());
        assertTrue(read instanceof FellTrees.State, "no type field predates a second kind existing");
    }

    @Test
    void aPlantClearingComesBackWithItsStripsAndWhoFailed() {
        ClearPlants.State before = new ClearPlants.State(new Region(new Pos(0, 48, 0), new Pos(15, 112, 15)),
                0.5, List.of(0, 2), List.of(new ClearPlants.Failure(1, CREW, 3)),
                List.of(new Gather.Cooldown(CREW, 900L)), 400L);
        var encoded = PartyBoardCodecs.PROJECT.encodeStart(JsonOps.INSTANCE, before).getOrThrow();
        assertEquals("clear_plants", encoded.getAsJsonObject().get("type").getAsString());
        assertEquals(before, PartyBoardCodecs.PROJECT.parse(JsonOps.INSTANCE, encoded).getOrThrow());
    }

    @Test
    void aRowSavedUnderTheProjectsOldNameStillReads() {
        PartyBoard.Row row = new PartyBoard.Row(state(FellTrees.Phase.WORKING, List.of()), List.of());
        var encoded = PartyBoardCodecs.ROW.encodeStart(JsonOps.INSTANCE, row).getOrThrow();
        encoded.getAsJsonObject().getAsJsonObject("project").addProperty("type", "clear_area");
        assertEquals(row, PartyBoardCodecs.ROW.parse(JsonOps.INSTANCE, encoded).getOrThrow(),
                "a board saved before 2026-10-01 keeps its felling");
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
    void aGatherRowComesBackWithItsLedgerAndItsHolds() {
        AgentId alice = AgentId.random();
        PartyId party = PartyId.of(java.util.UUID.randomUUID());
        Gather.State before = new Gather.State("logs", 64, 0.5, party, "carry",
                List.of(new Pos(11, 64, 10), new Pos(12, 64, 10)),
                List.of(new Gather.Reading(new Pos(11, 64, 10), 24, 900L),
                        new Gather.Reading(new Pos(12, 64, 10), 8, 1_200L)),
                List.of(new Gather.Trip(alice, 16)), List.of());
        List<PartyBoard.Hold> holds =
                List.of(new PartyBoard.Hold(new WorkKey.ForMember(WorkKey.GATHER, alice), alice));

        PartyBoard.Row after = roundTrip(new PartyBoard.Row(before, holds));

        assertEquals("gather",
                PartyBoardCodecs.ROW.encodeStart(JsonOps.INSTANCE, new PartyBoard.Row(before, holds))
                        .getOrThrow().getAsJsonObject().getAsJsonObject("project")
                        .get("type").getAsString());
        assertEquals(before, after.project());
        assertEquals(holds, after.holds(),
                "a member-keyed hold is what hands each settler their own trip back on load");
        assertEquals(List.of(new Gather.Trip(alice, 16)), ((Gather.State) after.project()).trips(),
                "and the SIZE beside it, which nothing else in the row can re-derive");
    }

    @Test
    void aGatherReadingKeepsTheTickItWasTakenAt() {
        // Without it a restart makes every belief look freshly taken, and a member arriving with
        // an hour-old memory overwrites a newer reading — the ledger is the chest as LAST read.
        Gather.State before = new Gather.State("logs", 64, 0.5,
                PartyId.of(java.util.UUID.randomUUID()), "carry", List.of(new Pos(11, 64, 10)),
                List.of(new Gather.Reading(new Pos(11, 64, 10), 24, 900L)), List.of(), List.of());

        Gather.State after = (Gather.State) roundTrip(new PartyBoard.Row(before, List.of()))
                .project();

        assertEquals(900L, after.readings().get(0).at());
        assertEquals(24, after.readings().get(0).count());
    }

    @Test
    void aCookComesBackWithItsCampfireCountAndCooldowns() {
        AgentId alice = AgentId.random();
        dev.luizloyola.autarkia.core.board.Cook.State before = new dev.luizloyola.autarkia.core.board.Cook.State(
                new Pos(3, 64, 2), 12, 0.4, false, List.of(new Gather.Cooldown(alice, 900L)), 450L);

        assertEquals(before, roundTrip(new PartyBoard.Row(before, List.of())).project());
    }

    @Test
    void aGatherCooldownSurvivesTheFile() {
        // Without it a reload puts a failing member straight back in front of the same trip they
        // just proved impossible — the whole defect this pacing exists to close.
        AgentId alice = AgentId.random();
        Gather.State before = new Gather.State("logs", 64, 0.5,
                PartyId.of(java.util.UUID.randomUUID()), "carry", List.of(), List.of(), List.of(),
                List.of(new Gather.Cooldown(alice, 12_345L)));

        Gather.State after = (Gather.State) roundTrip(new PartyBoard.Row(before, List.of()))
                .project();

        assertEquals(List.of(new Gather.Cooldown(alice, 12_345L)), after.cooldowns());
    }

    @Test
    void aMemberKeyedHoldRoundTrips() {
        AgentId alice = AgentId.random();
        PartyBoard.Hold hold = new PartyBoard.Hold(
                new WorkKey.ForMember(WorkKey.GATHER, alice), alice);
        var encoded = PartyBoardCodecs.HOLD.encodeStart(JsonOps.INSTANCE, hold).getOrThrow();
        assertEquals(hold, PartyBoardCodecs.HOLD.parse(JsonOps.INSTANCE, encoded).getOrThrow());
    }

    /** The gather row a hand-written {@code spec} needs around it to be a whole project. */
    private static JsonObject gatherRow() {
        JsonObject project = new JsonObject();
        project.addProperty("type", "gather");
        project.addProperty("target", 32);
        project.add("yard", PartyBoardCodecs.POS.encodeStart(JsonOps.INSTANCE,
                new Pos(0, 64, 0)).getOrThrow());
        project.addProperty("priority", 0.5);
        project.add("party", UUIDUtil.CODEC.encodeStart(JsonOps.INSTANCE,
                UUID.randomUUID()).getOrThrow());
        // "even" was EvenSplit's id before the 2026-08-28 pool rewrite deleted it. Left as-is: it's
        // what a pre-rewrite file would actually carry, so restoring this row exercises
        // Gather.restore's real fallback to CarrySplit rather than a made-up unknown string.
        project.addProperty("split", "even");
        return project;
    }

    @Test
    void aGatherPostedForOneItemWritesTheIdsAndNotItsDerivedName() {
        // What `board post gather <item>` builds: ItemSpec.anyOf, registered by the command in the
        // running jvm and by NOBODY at boot — no bootstrap declares "oak_log" the way Stock does
        // "logs". Writing that derived name alone is a dead handle: restore comes back empty, the
        // board counts the project unknown, and refuseUnknown will not start the world again.
        ItemSpec posted = ItemSpec.anyOf(Set.of("minecraft:oak_log"));
        Gather.State before = new Gather.State(posted.name(), 64, 0.5,
                PartyId.of(UUID.randomUUID()), "carry", List.of(), List.of(), List.of(), List.of());
        PartyBoard.Row row = new PartyBoard.Row(before, List.of());

        JsonObject written = PartyBoardCodecs.ROW.encodeStart(JsonOps.INSTANCE, row)
                .getOrThrow().getAsJsonObject().getAsJsonObject("project");

        assertTrue(written.get("spec").isJsonArray(),
                "a literal spec travels as its ids — \"" + posted.name() + "\" is a name only this "
                        + "jvm can resolve");
        assertEquals("minecraft:oak_log",
                written.get("spec").getAsJsonArray().get(0).getAsString());

        Gather back = Gather.restore((Gather.State) roundTrip(row).project(), 0L).orElseThrow(
                () -> new AssertionError("the only gather the command can post must reload"));
        assertTrue(back.spec().matches("minecraft:oak_log"), "and match what it was posted for");
    }

    @Test
    void aGatherRowCarryingIdsRestoresASpecNothingDeclares() {
        // The load half, from a file this jvm did not write. Nothing declares "cut_copper", so
        // re-canonicalising the ids through anyOf is the only thing that can put the name back
        // in the registry Gather.restore then looks it up in.
        JsonArray ids = new JsonArray();
        ids.add("minecraft:cut_copper");
        JsonObject project = gatherRow();
        project.add("spec", ids);

        ProjectState read = PartyBoardCodecs.PROJECT.parse(JsonOps.INSTANCE, project).getOrThrow();

        Gather back = Gather.restore((Gather.State) read, 0L).orElseThrow(
                () -> new AssertionError("a saved gather whose spec nobody declares must reload"));
        assertTrue(back.spec().matches("minecraft:cut_copper"));
        assertFalse(back.spec().matches("minecraft:oak_log"), "and match nothing else");
    }

    @Test
    void aGatherRowWrittenAsABareNameStillReads() {
        // Every gather written before the ids were carried is name-shaped, and for a DECLARED spec
        // a name is still the whole handle — Stock puts "logs" back at class load, as a bootstrap
        // does at boot. An unknown name is deliberately not an error here: it has to reach
        // refuseUnknown, which stops the world rather than quietly emptying a party's board.
        JsonObject project = gatherRow();
        project.addProperty("spec", Stock.LOGS.name());

        Gather.State read =
                (Gather.State) PartyBoardCodecs.PROJECT.parse(JsonOps.INSTANCE, project).getOrThrow();

        assertEquals("logs", read.spec());
        assertEquals(Stock.LOGS, Gather.restore(read, 0L).orElseThrow().spec());

        project.addProperty("spec", "dilithium");
        assertTrue(PartyBoardCodecs.PROJECT.parse(JsonOps.INSTANCE, project).result().isPresent(),
                "an undeclared name decodes; refusing the WORLD is PartyBoardData's job, not the "
                        + "codec's — a row that fails here drops inside a party that still counts");
    }

    @Test
    void aSetUpComesBackAtTheStationItWasOn() {
        SetUp.State state = new SetUp.State(List.of(SetUp.WORKBENCH, SetUp.STORE),
                new Pos(10, 64, 10), 0.5, 1,
                List.of(new Gather.Cooldown(AgentId.of(new java.util.UUID(1, 2)), 900L)), 400L,
                List.of(new SetUp.Failures(AgentId.of(new java.util.UUID(1, 2)), 3)));
        PartyBoard.Row row = new PartyBoard.Row(state, List.of());

        PartyBoard.Row back = roundTrip(row);

        assertEquals(row, back);
        assertEquals("set_up", PartyBoardCodecs.ROW.encodeStart(JsonOps.INSTANCE, row).getOrThrow()
                .getAsJsonObject().getAsJsonObject("project").get("type").getAsString());
    }

    @Test
    void aFlattenComesBackWithEveryColumnWhereItGotTo() {
        dev.luizloyola.autarkia.core.board.Flatten.State before = new dev.luizloyola.autarkia.core.board.Flatten.State(
                new Region(new Pos(0, 64, 0), new Pos(15, 64, 15)), 1, 64, "BALANCED", 0.5,
                List.of(new dev.luizloyola.autarkia.core.board.Flatten.Col(1, 2, 67, 65, 66, false),
                        new dev.luizloyola.autarkia.core.board.Flatten.Col(-3, 9, 60, 63, 60, true)),
                List.of(new dev.luizloyola.autarkia.core.board.Flatten.Cooldown("cut", new Pos(0, 66, 0), 2, 4800L)),
                1234L);
        PartyBoard.Row after = roundTrip(new PartyBoard.Row(before, List.of()));
        assertEquals(before, after.project());
    }

    @Test
    void aBuildComesBackWithItsStepsAndWhichStand() {
        var door = new dev.luizloyola.autarkia.core.builder.Laying(dev.luizloyola.autarkia.core.builder.Section.DOORS,
                new dev.luizloyola.anima.core.brain.act.Placing("minecraft:oak_door", new Pos(4, 65, 2),
                        "minecraft:oak_door", java.util.Map.of("facing", "east", "hinge", "left")),
                List.of(new Pos(4, 66, 2)), new Pos(6, 65, 2), 1);
        var slab = new dev.luizloyola.autarkia.core.builder.Laying(dev.luizloyola.autarkia.core.builder.Section.CEILING,
                new dev.luizloyola.anima.core.brain.act.Placing("minecraft:oak_slab", new Pos(4, 69, 2),
                        "minecraft:oak_slab", java.util.Map.of("type", "double")),
                List.of(), new Pos(4, 66, 1), 2).in(4, 9);
        var pot = new dev.luizloyola.autarkia.core.builder.Laying(dev.luizloyola.autarkia.core.builder.Section.INTERIOR,
                new dev.luizloyola.anima.core.brain.act.Placing("minecraft:flower_pot", new Pos(5, 66, 2),
                        "minecraft:potted_poppy", java.util.Map.of()),
                List.of(), new Pos(5, 65, 4), 1, List.of(new dev.luizloyola.autarkia.core.builder.Laying.Use(
                        java.util.Set.of("minecraft:poppy"), true)));
        var before = new dev.luizloyola.autarkia.core.board.Build.State(java.util.UUID.randomUUID(),
                "autarkia:basic_wooden_house", 0.5, List.of(door, slab, pot), List.of(0), List.of(0, 2), List.of(),
                List.of(dev.luizloyola.anima.core.agent.AgentId.random()),
                List.of(new dev.luizloyola.autarkia.core.board.Build.Cooldown(3, 900L)),
                List.of(new dev.luizloyola.autarkia.core.board.Build.Shortage(5,
                        java.util.Map.of("minecraft:oak_stairs", 7), 1200L)),
                java.util.Map.of("minecraft:dark_oak_log", 600L));
        PartyBoard.Row after = roundTrip(new PartyBoard.Row(before, List.of()));
        assertEquals(before, after.project());
    }
}
