package dev.luizloyola.autarkia.core.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.board.WorkItem;
import dev.luizloyola.anima.core.brain.knowledge.CoverageGrid;
import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.brain.knowledge.Region;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.Idle;
import dev.luizloyola.anima.core.brain.task.Task;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The frontier, the slices, the ledger and the refusal rule — everything that decides whether a box
 * gets cleared, proven without a world, a body or a block of perception.
 *
 * <p>Blind: a survey here is a member handing back what they "remember". That is what
 * a real one will be. The walking is step 2's and changes no rule below.
 */
class ClearAreaTest {

    /** A kind of place that is only ever a test's. */
    private static final PoiKind THING = PoiKind.register("clear_test_thing", 1, "");

    /** A clearing that can survey and whose tasks do nothing — the rules are the subject here. */
    /** What the last survey the double handed out was told — the coverage seam, observed. */
    private static java.util.Map<Pos, Integer> lastKnown = java.util.Map.of();
    private static dev.luizloyola.anima.core.brain.knowledge.Coverage lastCoverage =
            dev.luizloyola.anima.core.brain.knowledge.Coverage.NONE;

    private record TestClearing(boolean surveys) implements Clearing {
        @Override
        public String id() {
            return "test_things";
        }

        @Override
        public PoiKind kind() {
            return THING;
        }

        @Override
        public String label() {
            return "things";
        }

        @Override
        public Task survey(Region slice, java.util.Map<Pos, Integer> known,
                dev.luizloyola.anima.core.brain.knowledge.Coverage coverage) {
            lastKnown = java.util.Map.copyOf(known);
            lastCoverage = coverage;
            return new Idle(1);
        }

        @Override
        public Task clear(Pos anchor) {
            return new Idle(1);
        }
    }

    private static final Clearing ABLE = new TestClearing(true);
    private static final Clearing UNABLE = new TestClearing(false);

    /** A box exactly one slice across, so sweeping the whole box is a single errand. */
    private static Region oneSlice() {
        return new Region(new Pos(0, 60, 0), new Pos(10, 70, 10));
    }

    /** Two slices at {@link ClearArea#SLICE_SIZE}, so one can be swept while the other is not. */
    private static Region twoSlices() {
        return new Region(new Pos(0, 60, 0), new Pos(95, 70, 47));
    }

    private static ClearArea posted(Clearing clearing, Region bounds) {
        ClearArea project = new ClearArea(clearing, bounds, 0.5);
        project.tick(0L);
        return project;
    }

    /** The survey errand on offer — position in the offer is not part of any rule here. */
    private static WorkItem surveyItem(ClearArea project) {
        return project.open().stream()
                .filter(item -> item.describe().startsWith("survey")).findFirst().orElseThrow();
    }

    /** The clear errand on offer, when exactly one is. */
    private static WorkItem clearItem(ClearArea project) {
        return project.open().stream()
                .filter(item -> item.describe().startsWith("clear")).findFirst().orElseThrow();
    }

    // ── where the wood goes ──────────────────────────────────────────────────────────────────

    private static final Pos YARD = new Pos(6, 60, 6);

    @Test
    void aBoxWithoutADestinationIsExactlyWhatItWas() {
        ClearArea project = posted(ABLE, oneSlice());

        assertTrue(project.yard().isEmpty(), "no destination named, none invented");
        assertFalse(project.describe().contains("yard"), "and the readout says nothing about one");
    }

    @Test
    void aNamedDestinationIsCarriedAndSaidOutLoud() {
        ClearArea project = new ClearArea(ABLE, oneSlice(), 0.5, YARD);
        project.tick(0L);

        assertEquals(YARD, project.yard().orElseThrow());
        assertTrue(project.describe().contains("yard"),
                "an operator who asked for a destination should see it in the readout");
    }

    @Test
    void theDestinationSurvivesASnapshotRoundTrip() {
        ClearArea project = new ClearArea(ABLE, oneSlice(), 0.5, YARD);
        project.tick(0L);

        ClearArea restored = ClearArea.restore(project.snapshot(), 0L).orElseThrow();

        assertEquals(YARD, restored.yard().orElseThrow());
    }

    @Test
    void aBoxSavedBeforeYardsExistedStillLoads() {
        ClearArea plain = posted(ABLE, oneSlice());

        ClearArea restored = ClearArea.restore(plain.snapshot(), 0L).orElseThrow();

        assertTrue(restored.yard().isEmpty(), "no destination, no migration, no surprise chest");
    }

    @Test
    void itRemembersWhereTheChestActuallyWent() {
        ClearArea project = new ClearArea(ABLE, oneSlice(), 0.5, YARD);
        project.tick(0L);
        BoardBrainContext ctx = new BoardBrainContext();
        // The hauler built it a block off the hint, because the hint was a hint.
        ctx.remember(dev.luizloyola.anima.core.store.Store.POI, new Pos(7, 60, 6));

        project.completed(project.open().get(0), ctx);

        assertEquals(List.of(new Pos(7, 60, 6)), project.yardChests(),
                "the readout should name where the wood is, not where it was asked for");
        assertTrue(project.describe().contains("1 chest"));
    }

    @Test
    void aChestNowhereNearTheHintIsNotThisProjectsYard() {
        ClearArea project = new ClearArea(ABLE, oneSlice(), 0.5, YARD);
        project.tick(0L);
        BoardBrainContext ctx = new BoardBrainContext();
        ctx.remember(dev.luizloyola.anima.core.store.Store.POI, new Pos(900, 60, 900));

        project.completed(project.open().get(0), ctx);

        assertTrue(project.yardChests().isEmpty(),
                "a worker's own chest across the map is not the project's yard");
    }

    @Test
    void aClearItemHaulsOnlyWhenThereIsAYard() {
        ClearArea plain = posted(ABLE, oneSlice());
        plain.completed(plain.open().get(0), ctxThatSaw(new Pos(3, 60, 3)));
        Task withoutYard = plain.open().stream()
                .filter(item -> item.describe().startsWith("clear")).findFirst().orElseThrow()
                .root();

        assertFalse(withoutYard instanceof HaulingErrand,
                "no destination, and the root is byte-for-byte what it always was");

        ClearArea withYard = new ClearArea(ABLE, oneSlice(), 0.5, YARD);
        withYard.tick(0L);
        withYard.completed(withYard.open().get(0), ctxThatSaw(new Pos(3, 60, 3)));
        Task hauling = withYard.open().stream()
                .filter(item -> item.describe().startsWith("clear")).findFirst().orElseThrow()
                .root();

        assertTrue(hauling instanceof HaulingErrand,
                "with one, felling is followed by taking the load over when laden");
    }

    /** A context whose settler remembers a thing to clear at {@code anchor}. */
    private static BoardBrainContext ctxThatSaw(Pos anchor) {
        BoardBrainContext ctx = new BoardBrainContext();
        ctx.remember(THING, anchor);
        return ctx;
    }

    // ── a report only ever adds ──────────────────────────────────────────────────────────────

    @Test
    void aReportOnlyEverAddsAnAnchorTheLedgerHasNeverHeardOf() {
        // The one rule harvest has, and the regression test for the 197 cleared / 186 / 197 / 186
        // cycle (live, 2026-08-12): re-reading rows the ledger already holds reopened cleared
        // anchors and sent people to fell ghosts. Stated as a rule now, not as a tick comparison.
        ClearArea project = posted(ABLE, twoSlices());
        BoardBrainContext ctx = new BoardBrainContext();
        Pos felled = new Pos(3, 60, 3);
        Pos stubborn = new Pos(11, 60, 11);
        ctx.remember(THING, felled);
        ctx.remember(THING, stubborn);
        project.completed(surveyItem(project), ctx);
        project.completed(itemAt(project, felled), ctx);
        for (int attempt = 0; attempt < ClearArea.REFUSE_AFTER; attempt++) {
            project.failed(itemAt(project, stubborn), ctx);
            ctx.advance(ClearArea.FAIL_COOLDOWN);
            project.tick(ctx.now());
        }

        // The second sweep still remembers all of it, and finds one more.
        Pos fresh = new Pos(60, 60, 20);
        ctx.remember(THING, fresh);
        project.completed(surveyItem(project), ctx);

        assertEquals(ClearArea.TargetState.CLEARED, project.ledger().get(felled).state(),
                "a stale memory of something already felled is not evidence it is back");
        assertEquals(ClearArea.TargetState.REFUSED, project.ledger().get(stubborn).state(),
                "and re-reporting a refusal would restart the loop REFUSE_AFTER exists to end");
        assertEquals(ClearArea.TargetState.OPEN, project.ledger().get(fresh).state());
    }

    // ── what a report may say ────────────────────────────────────────────────────────────────

    @Test
    void aChopperReportsTheTreesTheyWalkedPast() {
        ClearArea project = posted(ABLE, oneSlice());
        BoardBrainContext ctx = ctxThatSaw(new Pos(3, 60, 3));
        project.completed(project.open().get(0), ctx);
        WorkItem tree = project.open().stream()
                .filter(i -> i.describe().startsWith("clear")).findFirst().orElseThrow();
        // On the way to the tree the chopper individuates another one.
        ctx.remember(THING, new Pos(8, 60, 8));

        project.completed(tree, ctx);

        assertTrue(project.ledger().containsKey(new Pos(8, 60, 8)),
                "a tree spotted mid-chop used to wait for a verify pass to re-walk that ground");
        assertFalse(project.finished(), "and it is on offer now, not a cycle later");
    }

    @Test
    void aReportNeverRewritesARowTheLedgerAlreadyHolds() {
        ClearArea project = posted(ABLE, oneSlice());
        Pos anchor = new Pos(3, 60, 3);
        BoardBrainContext ctx = ctxThatSaw(anchor);
        project.completed(project.open().get(0), ctx);
        WorkItem tree = project.open().stream()
                .filter(i -> i.describe().startsWith("clear")).findFirst().orElseThrow();

        // The memory of the felled tree lingers — the near field has not re-probed that column yet.
        project.completed(tree, ctx);

        assertEquals(ClearArea.TargetState.CLEARED, project.ledger().get(anchor).state(),
                "resurrecting a CLEARED row is the 197/186 cycle; the rule, not a cut-off tick, "
                        + "is what forbids it now");
    }

    @Test
    void aStaleMemoryOfSomethingNobodyHasFelledIsStillWorthBanking() {
        BoardBrainContext ctx = new BoardBrainContext();
        ctx.advance(5_000L);
        ctx.rememberSeenAt(THING, new Pos(3, 60, 3), 500L);
        ClearArea project = new ClearArea(ABLE, oneSlice(), 0.5);
        project.tick(5_000L);

        project.completed(project.open().get(0), ctx);

        assertEquals(1, project.ledger().size(),
                "nobody has cleared it, so it is probably still standing — and a chop that finds "
                        + "nothing SUCCEEDS, so being wrong costs one short walk");
    }

    @Test
    void aWorkerWhoFailedStillReportsWhatTheySaw() {
        ClearArea project = posted(ABLE, oneSlice());
        BoardBrainContext ctx = ctxThatSaw(new Pos(3, 60, 3));
        project.completed(project.open().get(0), ctx);
        WorkItem tree = project.open().stream()
                .filter(i -> i.describe().startsWith("clear")).findFirst().orElseThrow();
        ctx.remember(THING, new Pos(8, 60, 8));

        project.failed(tree, AgentId.random(), ctx);

        assertTrue(project.ledger().containsKey(new Pos(8, 60, 8)),
                "they walked there and their near field ran; the errand's outcome is a different fact");
    }

    // ── the coverage the box keeps ───────────────────────────────────────────────────────────

    @Test
    void aPreemptedSurveyResumesWhereItStopped() {
        ClearArea project = posted(ABLE, oneSlice());
        var errand = project.open().get(0);

        errand.root();                      // granted: the double captures the sink
        lastCoverage.settled(new Pos(0, 60, 0));
        lastCoverage.settled(new Pos(8, 60, 0));
        errand.root();                      // preempted, then re-granted: a FRESH task

        assertTrue(lastKnown.keySet().containsAll(
                        java.util.Set.of(new Pos(0, 60, 0), new Pos(8, 60, 0))),
                "the sweep resumes; it does not walk the box again from the treeline");
    }

    @Test
    void coverageSurvivesASnapshotRoundTrip() {
        ClearArea project = posted(ABLE, oneSlice());
        project.open().get(0).root();
        lastCoverage.settled(new Pos(0, 60, 0));

        ClearArea restored = ClearArea.restore(project.snapshot(), 0L).orElseThrow();
        restored.open().get(0).root();

        assertTrue(lastKnown.keySet().contains(new Pos(0, 60, 0)),
                "and it survives a restart, which is the half a reload used to lose");
    }

    // ── the frontier is the offer ────────────────────────────────────────────────────────────

    @Test
    void aFreshBoxOffersOnlySurveys() {
        ClearArea project = posted(ABLE, twoSlices());

        assertEquals(2, project.open().size());
        assertTrue(project.open().stream().allMatch(item -> item.describe().startsWith("survey")));
    }

    @Test
    void aTreeReportedMidSweepIsOfferedWhileTheRestIsStillBeingWalked() {
        ClearArea project = posted(ABLE, twoSlices());
        BoardBrainContext ctx = ctxThatSaw(new Pos(3, 60, 3));

        project.completed(project.open().get(0), ctx);

        assertTrue(project.open().stream().anyMatch(i -> i.describe().startsWith("clear")),
                "the SURVEYING barrier is gone: the first slice reported puts trees in front of "
                        + "the crew while the rest of the box is still unwalked");
        assertTrue(project.open().stream().anyMatch(i -> i.describe().startsWith("survey")),
                "and the unswept slice is still on offer beside it");
    }

    @Test
    void aSliceEverybodyHasAlreadyCoveredIsNeverMintedAsAnErrand() {
        ClearArea project = posted(ABLE, twoSlices());
        Region first = project.slices().get(0);
        for (int x = first.min().x(); x <= first.max().x(); x += CoverageGrid.CELL) {
            for (int z = first.min().z(); z <= first.max().z(); z += CoverageGrid.CELL) {
                project.covered().markFull(new Pos(x, first.min().y(), z));
            }
        }
        project.tick(0L);

        assertEquals(1, project.open().size(), "only the slice nobody has been over");
    }

    @Test
    void theBoxClosesWhenTheFrontierIsEmptyAndNothingIsStanding() {
        ClearArea project = posted(ABLE, oneSlice());
        BoardBrainContext ctx = new BoardBrainContext();

        project.completed(project.open().get(0), ctx);

        assertTrue(project.finished(), "nothing found and nothing left unswept is done");
        assertTrue(project.open().isEmpty());
    }

    @Test
    void theBoxDoesNotCloseWhileAnyTargetIsStillOpen() {
        ClearArea project = posted(ABLE, oneSlice());
        BoardBrainContext ctx = ctxThatSaw(new Pos(3, 60, 3));

        project.completed(project.open().get(0), ctx);

        assertFalse(project.finished(), "a tree is standing in it");
    }

    @Test
    void aTargetCoolingOffKeepsTheBoxOpen() {
        ClearArea project = posted(ABLE, oneSlice());
        BoardBrainContext ctx = ctxThatSaw(new Pos(3, 60, 3));
        project.completed(project.open().get(0), ctx);
        WorkItem tree = clearItem(project);

        project.failed(tree, AgentId.random(), ctx);

        assertFalse(project.finished(),
                "OPEN and waiting out a cooldown is still OPEN — the box is not clear");
    }

    @Test
    void thereIsNoSecondSweepOfGroundSomebodyAlreadyCovered() {
        ClearArea project = posted(ABLE, oneSlice());
        BoardBrainContext ctx = ctxThatSaw(new Pos(3, 60, 3));
        project.completed(project.open().get(0), ctx);
        WorkItem tree = clearItem(project);

        project.completed(tree, ctx);

        assertTrue(project.finished(),
                "the verify pass is what this design removes — the ground was already covered");
    }

    // ── how the box is cut up ────────────────────────────────────────────────────────────────

    @Test
    void aBoxDividesIntoWholeSlicesAndTheGridCoversIt() {
        // Two slices wide, two deep — which one comes out shorter is now the cell grid's call,
        // not simply "the last one".
        Region big = new Region(new Pos(0, 60, 0), new Pos(60, 70, 50));
        ClearArea project = posted(ABLE, big);
        assertEquals(4, project.slices().size());
        assertEquals(new Pos(0, 60, 0), project.slices().get(0).min());
        assertEquals(new Pos(60, 70, 50), project.slices().get(3).max());
        assertEquals(4, project.open().size());
    }

    /** Widths of the slices along x, in order, for a box only one block deep. */
    private static List<Integer> widths(int fromX, int toX) {
        return posted(ABLE, new Region(new Pos(fromX, 60, 0), new Pos(toX, 70, 0)))
                .slices().stream()
                .map(slice -> slice.max().x() - slice.min().x() + 1)
                .toList();
    }

    @Test
    void aRemainderIsSharedOutRatherThanLeftAsASliver() {
        // SLICE_SIZE is a ceiling, so the count comes first and the span is split evenly between
        // that many. One block over a whole slice used to mean a full slice and a 1-block ribbon.
        assertEquals(List.of(48, 48), widths(0, 95));
        // The split is decided in whole CELLs (8 blocks), not blocks: 49 needs 7 cells to cover
        // (ceil(49/8)), splits 4/3 between two slices, and the interior boundary lands at 4×8=32 —
        // the last slice is whatever span is actually left over, 17.
        assertEquals(List.of(32, 17), widths(0, 48));
        assertEquals(List.of(32, 40, 25), widths(0, 96));
        // Not evenly BALANCED in blocks when the span isn't a multiple of the cell size — the cell
        // count is a ceiling over the real span — but the split depends only on the span, so it is
        // still the same shape wherever the box sits.
        assertEquals(List.of(32, 40, 25), widths(-1000, -904));
    }

    @Test
    void everySliceCornerSitsOnTheBoxesCellGrid() {
        // 130 across at a 48 ceiling is three slices; unaligned they would land on 43 and 87.
        ClearArea project = posted(ABLE,
                new Region(new Pos(0, 60, 0), new Pos(129, 70, 129)));

        for (Region slice : project.slices()) {
            assertEquals(0, (slice.min().x() - 0) % CoverageGrid.CELL,
                    "a slice whose grid is offset from the box's gets no discount from coverage "
                            + "a chopper banked, because a corner on one grid names nothing on the other");
            assertEquals(0, (slice.min().z() - 0) % CoverageGrid.CELL);
        }
    }

    @Test
    void noSliceExceedsTheCeilingOrComesBackEmpty() {
        // One span is not proof: 137 across at n=3 used to round an interior boundary DOWN by
        // rounding blocks after deciding the split in blocks, growing the far gap to 49 with nothing
        // to absorb the loss — one over the ceiling. Sweeping many spans, on both axes together via
        // a square box, is what catches a rounding defect a single lucky span does not. 137 is
        // included explicitly because it is the span that actually caught it.
        for (int span = 1; span <= 300; span++) {
            assertSliceInvariants(span);
        }
        assertSliceInvariants(137);
    }

    /** Every ceiling/empty/grid invariant {@code cuts} owes, for a box {@code span} blocks square. */
    private static void assertSliceInvariants(int span) {
        ClearArea project =
                posted(ABLE, new Region(new Pos(0, 60, 0), new Pos(span - 1, 70, span - 1)));
        for (Region slice : project.slices()) {
            int wide = slice.max().x() - slice.min().x() + 1;
            int deep = slice.max().z() - slice.min().z() + 1;
            assertTrue(wide > 0 && wide <= ClearArea.SLICE_SIZE, "span " + span + " wide: " + wide);
            assertTrue(deep > 0 && deep <= ClearArea.SLICE_SIZE, "span " + span + " deep: " + deep);
            assertEquals(0, slice.min().x() % CoverageGrid.CELL, "span " + span + " x corner");
            assertEquals(0, slice.min().z() % CoverageGrid.CELL, "span " + span + " z corner");
        }
    }

    @Test
    void theSlicesTileTheBoxWithNoGapNoOverlapAndNothingOversized() {
        Region box = new Region(new Pos(-7, 60, 12), new Pos(123, 70, 60));
        ClearArea project = posted(ABLE, box);
        int covered = 0;
        for (Region slice : project.slices()) {
            int wide = slice.max().x() - slice.min().x() + 1;
            int deep = slice.max().z() - slice.min().z() + 1;
            assertTrue(wide <= ClearArea.SLICE_SIZE && deep <= ClearArea.SLICE_SIZE,
                    "slice " + wide + "×" + deep + " is over the ceiling");
            assertTrue(wide > 0 && deep > 0, "an empty slice is still a whole errand");
            covered += wide * deep;
        }
        // Area adds up and the corners are the box's own — enough together to rule out both a gap
        // and an overlap.
        assertEquals(131 * 49, covered);
        assertEquals(box.min(), project.slices().get(0).min());
        assertEquals(box.max(), project.slices().get(project.slices().size() - 1).max());
    }

    @Test
    void nobodyIsOfferedAnythingWhileNothingCanSurvey() {
        ClearArea project = posted(UNABLE, oneSlice());
        assertTrue(project.open().isEmpty());
        // And the readout says why, rather than looking like a project with nothing to do.
        assertTrue(project.describe().contains("nobody can survey"));
        assertFalse(project.finished());
    }

    // ── the loop ─────────────────────────────────────────────────────────────────────────────

    @Test
    void everythingFoundAndFelledClosesTheBox() {
        ClearArea project = posted(ABLE, oneSlice());
        BoardBrainContext ctx = new BoardBrainContext();
        ctx.remember(THING, new Pos(3, 60, 3));
        ctx.remember(THING, new Pos(7, 60, 8));

        project.completed(project.open().get(0), ctx);
        assertEquals(2, project.open().size(), "both are on offer the moment they are reported");

        for (WorkItem item : List.copyOf(project.open())) {
            project.completed(item, ctx);
        }

        assertEquals(ClearArea.Phase.DONE, project.phase());
        assertTrue(project.describe().contains("2 cleared"));
    }

    @Test
    void onlyWhatIsInsideTheBoxIsTaken() {
        ClearArea project = posted(ABLE, oneSlice());
        BoardBrainContext ctx = new BoardBrainContext();
        ctx.remember(THING, new Pos(3, 60, 3));
        ctx.remember(THING, new Pos(300, 60, 300));
        ctx.remember(THING, new Pos(3, 200, 3)); // inside the footprint, above the box

        project.completed(project.open().get(0), ctx);
        assertEquals(1, project.ledger().size());
    }

    // ── giving up ────────────────────────────────────────────────────────────────────────────

    @Test
    void aTargetThatKeepsFailingIsRefusedAndTheProjectCanFinish() {
        ClearArea project = posted(ABLE, oneSlice());
        BoardBrainContext ctx = new BoardBrainContext();
        Pos stubborn = new Pos(3, 60, 3);
        ctx.remember(THING, stubborn);
        project.completed(project.open().get(0), ctx);

        for (int attempt = 1; attempt <= ClearArea.REFUSE_AFTER; attempt++) {
            assertEquals(1, project.open().size(), "attempt " + attempt + " should be on offer");
            project.failed(project.open().get(0), ctx);
            if (attempt < ClearArea.REFUSE_AFTER) {
                // A failure is paced: nothing is on offer until the cooldown runs out, or an agent
                // would burn every attempt within a second of the first.
                assertTrue(project.open().isEmpty(), "attempt " + attempt + " must cool down");
            }
            ctx.advance(ClearArea.FAIL_COOLDOWN);
            project.tick(ctx.now());
        }
        assertEquals(ClearArea.TargetState.REFUSED, project.ledger().get(stubborn).state());
        // Refused means gone from the offer for good — this is what stops "repeat until done"
        // from repeating forever over one thing nobody can remove. The last failure leaves nothing
        // un-swept and nothing standing, so the box closes on that same call.
        assertTrue(project.open().isEmpty());
        assertEquals(ClearArea.Phase.DONE, project.phase());
        assertTrue(project.describe().contains("1 refused"));
    }

    @Test
    void aRefusedAnchorIsNotReportedBackEither() {
        // Two slices, so the box stays open on the frontier while the refusal settles and a later
        // sweep still has somewhere to report from.
        ClearArea project = posted(ABLE, twoSlices());
        BoardBrainContext ctx = new BoardBrainContext();
        Pos stubborn = new Pos(3, 60, 3);
        ctx.remember(THING, stubborn);
        project.completed(surveyItem(project), ctx);
        for (int attempt = 0; attempt < ClearArea.REFUSE_AFTER; attempt++) {
            project.failed(itemAt(project, stubborn), ctx);
            ctx.advance(ClearArea.FAIL_COOLDOWN);
            project.tick(ctx.now());
        }
        assertEquals(ClearArea.TargetState.REFUSED, project.ledger().get(stubborn).state());

        // The next sweep reports it again, because it is still standing there in plain sight.
        project.completed(surveyItem(project), ctx);

        assertEquals(ClearArea.TargetState.REFUSED, project.ledger().get(stubborn).state(),
                "a refused target reported afresh would restart the loop it exists to end");
        assertTrue(project.finished());
    }

    @Test
    void aRefusedTargetGetsAnotherGoOnceItsNeighboursAreDown() {
        // A thing can be unreachable BECAUSE of what surrounds it, so a box that removed something
        // has changed the world and earned the refused ones a retry before it closes.
        ClearArea project = posted(ABLE, oneSlice());
        BoardBrainContext ctx = new BoardBrainContext();
        Pos stubborn = new Pos(3, 60, 3);
        Pos easy = new Pos(8, 60, 8);
        ctx.remember(THING, stubborn);
        ctx.remember(THING, easy);
        project.completed(project.open().get(0), ctx);

        // Refuse one, fell the other.
        for (int attempt = 0; attempt < ClearArea.REFUSE_AFTER; attempt++) {
            project.failed(itemAt(project, stubborn), ctx);
            ctx.advance(ClearArea.FAIL_COOLDOWN);
            project.tick(ctx.now());
        }
        assertEquals(ClearArea.TargetState.REFUSED, project.ledger().get(stubborn).state());
        project.completed(itemAt(project, easy), ctx);

        assertFalse(project.finished(), "the box would have closed, so the refusal gets its retry");
        assertEquals(ClearArea.TargetState.OPEN, project.ledger().get(stubborn).state(),
                "the trigger is the close, not the end of a round — there are no rounds");
    }

    @Test
    void aBoxThatFelledNothingDoesNotReopenAndEnds() {
        // The termination guarantee. Reopening costs a felled tree; a box that felled none has
        // changed nothing, so retrying would loop forever — which is what REFUSE_AFTER is for.
        ClearArea project = posted(ABLE, oneSlice());
        BoardBrainContext ctx = new BoardBrainContext();
        Pos stubborn = new Pos(3, 60, 3);
        ctx.remember(THING, stubborn);
        project.completed(project.open().get(0), ctx);
        for (int attempt = 0; attempt < ClearArea.REFUSE_AFTER; attempt++) {
            project.failed(itemAt(project, stubborn), ctx);
            ctx.advance(ClearArea.FAIL_COOLDOWN);
            project.tick(ctx.now());
        }

        assertEquals(ClearArea.TargetState.REFUSED, project.ledger().get(stubborn).state());
        assertEquals(ClearArea.Phase.DONE, project.phase(), "nothing changed, so nothing to retry");
        assertTrue(project.describe().contains("1 refused"),
                "the operator can see what stopped it rather than inferring it");
    }

    @Test
    void refusalsAreReopenedOnceAndOnlyOnce() {
        // The reopening is paid for out of felled targets, and the payment is spent. A second close
        // with nothing felled since must end the box rather than hand out another free retry.
        ClearArea project = posted(ABLE, oneSlice());
        BoardBrainContext ctx = new BoardBrainContext();
        Pos stubborn = new Pos(3, 60, 3);
        Pos easy = new Pos(8, 60, 8);
        ctx.remember(THING, stubborn);
        ctx.remember(THING, easy);
        project.completed(project.open().get(0), ctx);
        for (int attempt = 0; attempt < ClearArea.REFUSE_AFTER; attempt++) {
            project.failed(itemAt(project, stubborn), ctx);
            ctx.advance(ClearArea.FAIL_COOLDOWN);
            project.tick(ctx.now());
        }
        project.completed(itemAt(project, easy), ctx); // felling buys the one retry

        for (int attempt = 0; attempt < ClearArea.REFUSE_AFTER; attempt++) {
            project.failed(itemAt(project, stubborn), ctx);
            ctx.advance(ClearArea.FAIL_COOLDOWN);
            project.tick(ctx.now());
        }

        assertTrue(project.finished(), "the licence was spent; a box cannot retry on credit");
        assertEquals(ClearArea.TargetState.REFUSED, project.ledger().get(stubborn).state());
    }

    /** The open item standing at this anchor — the tests act through the board's own offers. */
    private static WorkItem itemAt(ClearArea project, Pos anchor) {
        return project.itemFor(new WorkKey(WorkKey.CLEAR, anchor)).orElseThrow();
    }

    @Test
    void oneWorkerFailingOverAndOverCannotCondemnATree() {
        // The 134-tree bug: one body in a try-fail loop refused a whole ledger, because failures
        // were charged to the tree rather than the worker. Giving up means several DIFFERENT people
        // could not.
        ClearArea project = posted(ABLE, oneSlice());
        BoardBrainContext ctx = new BoardBrainContext();
        Pos tree = new Pos(3, 60, 3);
        ctx.remember(THING, tree);
        project.completed(project.open().get(0), ctx);

        AgentId stuck = AgentId.random();
        for (int attempt = 0; attempt < ClearArea.REFUSE_AFTER * 3; attempt++) {
            project.failed(itemAt(project, tree), stuck, ctx);
            ctx.advance(ClearArea.FAIL_COOLDOWN);
            project.tick(ctx.now());
        }
        assertEquals(ClearArea.TargetState.OPEN, project.ledger().get(tree).state(),
                "one body failing repeatedly is evidence about the body, not about the tree");
    }

    @Test
    void enoughDifferentPeopleFailingIsWhatRefusesIt() {
        ClearArea project = posted(ABLE, oneSlice());
        BoardBrainContext ctx = new BoardBrainContext();
        Pos tree = new Pos(3, 60, 3);
        ctx.remember(THING, tree);
        project.completed(project.open().get(0), ctx);

        for (int attempt = 0; attempt < ClearArea.REFUSE_AFTER; attempt++) {
            project.failed(itemAt(project, tree), AgentId.random(), ctx);
            ctx.advance(ClearArea.FAIL_COOLDOWN);
            project.tick(ctx.now());
        }
        assertEquals(ClearArea.TargetState.REFUSED, project.ledger().get(tree).state());
    }

    @Test
    void aFailedSliceIsOfferedAgainAfterItsCooldown() {
        ClearArea project = posted(ABLE, oneSlice());
        BoardBrainContext ctx = new BoardBrainContext();
        project.failed(project.open().get(0), ctx);
        assertTrue(project.open().isEmpty());
        ctx.advance(ClearArea.FAIL_COOLDOWN);
        project.tick(ctx.now());
        assertEquals(1, project.open().size());
        assertFalse(project.finished(), "un-swept ground holds the box open however it got there");
    }

    // ── holds ────────────────────────────────────────────────────────────────────────────────

    @Test
    void anErrandSomebodyHoldsIsNeverWithdrawn() {
        ClearArea project = posted(ABLE, oneSlice());
        BoardBrainContext ctx = new BoardBrainContext();
        WorkItem taken = project.open().get(0);
        project.claimed(taken);
        project.failed(taken, ctx);
        // Failing releases it; the guard is about the OTHER route — a project deciding on its own
        // beat that it no longer wants an errand a worker is walking to.
        WorkItem again = project.open().isEmpty() ? null : project.open().get(0);
        assertTrue(again == null || again != taken);

        ctx.advance(ClearArea.FAIL_COOLDOWN);
        project.tick(ctx.now());
        WorkItem reoffered = project.open().get(0);
        project.claimed(reoffered);
        project.tick(ctx.now() + 1);
        assertSame(reoffered, project.open().get(0), "a held item must survive a beat unchanged");
    }

    @Test
    void anItemKeepsItsIdentityAcrossBeats() {
        // The board leases by IDENTITY, so re-minting on every ask would drop every hold.
        ClearArea project = posted(ABLE, oneSlice());
        WorkItem first = project.open().get(0);
        project.tick(1L);
        project.tick(2L);
        assertSame(first, project.open().get(0));
    }

    // ── durable names ────────────────────────────────────────────────────────────────────────

    @Test
    void everyOfferedItemHasADurableNameThatFindsItAgain() {
        ClearArea project = posted(ABLE, oneSlice());
        BoardBrainContext ctx = new BoardBrainContext();
        ctx.remember(THING, new Pos(3, 60, 3));
        project.completed(project.open().get(0), ctx);

        WorkItem item = project.open().get(0);
        Optional<WorkKey> key = project.keyOf(item);
        assertTrue(key.isPresent());
        assertEquals(WorkKey.CLEAR, key.get().flavour());
        assertSame(item, project.itemFor(key.get()).orElseThrow());
    }

    @Test
    void aNameForSomethingNoLongerOfferedFindsNothing() {
        ClearArea project = posted(ABLE, oneSlice());
        assertTrue(project.itemFor(new WorkKey(WorkKey.CLEAR, new Pos(1, 1, 1))).isEmpty());
    }

    // ── continuity ───────────────────────────────────────────────────────────────────────────

    @Test
    void aSavedProjectComesBackMidClearWithTheSameLedgerAndOffers() {
        Clearings.register(ABLE);
        ClearArea project = posted(ABLE, oneSlice());
        BoardBrainContext ctx = new BoardBrainContext();
        ctx.remember(THING, new Pos(3, 60, 3));
        ctx.remember(THING, new Pos(7, 60, 8));
        project.completed(project.open().get(0), ctx);
        project.completed(project.open().get(0), ctx);

        ClearArea back = ClearArea.restore(project.snapshot(), ctx.now()).orElseThrow();
        assertEquals(ClearArea.Phase.WORKING, back.phase());
        assertEquals(project.ledger(), back.ledger());
        assertEquals(1, back.open().size(), "the one target still standing is on offer again");
        assertEquals(project.describe(), back.describe());
    }

    @Test
    void aSavedProjectRemembersWhatItGaveUpOn() {
        Clearings.register(ABLE);
        ClearArea project = posted(ABLE, oneSlice());
        BoardBrainContext ctx = new BoardBrainContext();
        Pos stubborn = new Pos(3, 60, 3);
        ctx.remember(THING, stubborn);
        project.completed(project.open().get(0), ctx);
        for (int attempt = 0; attempt < ClearArea.REFUSE_AFTER; attempt++) {
            project.failed(project.open().get(0), ctx);
            ctx.advance(ClearArea.FAIL_COOLDOWN);
            project.tick(ctx.now());
        }

        ClearArea back = ClearArea.restore(project.snapshot(), ctx.now()).orElseThrow();
        assertEquals(ClearArea.TargetState.REFUSED, back.ledger().get(stubborn).state(),
                "a restart that forgot a refusal would let the loop back in through the store");
    }

    @Test
    void aSavedProjectRemembersHowFarTheSurveyGot() {
        Clearings.register(ABLE);
        Region big = new Region(new Pos(0, 60, 0), new Pos(60, 70, 50));
        ClearArea project = posted(ABLE, big);
        BoardBrainContext ctx = new BoardBrainContext();
        project.completed(project.open().get(0), ctx);
        assertEquals(3, project.open().size());

        ClearArea back = ClearArea.restore(project.snapshot(), ctx.now()).orElseThrow();
        assertEquals(3, back.open().size(), "a walked slice must not be walked again");
        assertEquals(project.describe(), back.describe(),
                "and the swept fraction comes back where it left off, not at zero");
    }

    @Test
    void aProjectWhoseClearingThisBuildLacksComesBackAsNothing() {
        Clearings.clear();
        ClearArea project = posted(ABLE, oneSlice());
        // Never silently an empty project: the store's job is to refuse the world, and it can only
        // do that if this says so rather than handing back something plausible.
        assertTrue(ClearArea.restore(project.snapshot(), 0L).isEmpty());
        Clearings.register(ABLE);
    }

    @Test
    void aSurveyItemKeepsItsNameAcrossARestart() {
        Clearings.register(ABLE);
        Region big = new Region(new Pos(0, 60, 0), new Pos(60, 70, 50));
        ClearArea project = posted(ABLE, big);
        WorkItem held = project.open().get(1);
        WorkKey key = project.keyOf(held).orElseThrow();

        ClearArea back = ClearArea.restore(project.snapshot(), 0L).orElseThrow();
        // A different object, the same errand: the member walking to it gets THAT one back rather
        // than the pool.
        assertTrue(back.itemFor(key).isPresent());
        assertEquals(held.describe(), back.itemFor(key).orElseThrow().describe());
    }

    // ── the board around it ──────────────────────────────────────────────────────────────────

    @Test
    void aPartyBoardHandsEveryHolderBackTheirOwnErrand() {
        Clearings.register(ABLE);
        PartyBoard board = new PartyBoard(
                dev.luizloyola.anima.core.social.PartyId.of(java.util.UUID.randomUUID()));
        Region big = new Region(new Pos(0, 60, 0), new Pos(60, 70, 50));
        ClearArea project = new ClearArea(ABLE, big, 0.5);
        board.post(project);
        board.tick(0L);

        AgentId alice = AgentId.random();
        AgentId bob = AgentId.random();
        WorkItem hers = project.open().get(0);
        WorkItem his = project.open().get(2);
        assertTrue(board.claim(hers, alice, 0L));
        assertTrue(board.claim(his, bob, 0L));
        // Taken before the reload, the only side the old objects exist on — why a durable name is
        // written down rather than derived later.
        WorkKey herSlice = project.keyOf(hers).orElseThrow();
        WorkKey hisSlice = project.keyOf(his).orElseThrow();

        List<PartyBoard.Row> saved = board.snapshot(0L);
        PartyBoard reloaded = new PartyBoard(board.party());
        assertEquals(0, reloaded.restore(saved, 0L));

        ClearArea back = (ClearArea) reloaded.projects().get(0);
        assertTrue(reloaded.holds(back.itemFor(herSlice).orElseThrow(), alice, 0L),
                "Alice must get HER slice back, not whichever one scores best");
        assertTrue(reloaded.holds(back.itemFor(hisSlice).orElseThrow(), bob, 0L));
        // And neither is on offer to anybody else.
        assertTrue(reloaded.bestFor(AgentId.random(), new BoardBrainContext(), 0L)
                .map(item -> back.keyOf(item).orElseThrow())
                .filter(key -> key.equals(herSlice) || key.equals(hisSlice))
                .isEmpty(), "a restored hold must not be re-offered to a third party");
    }

    @Test
    void aFinishedProjectIsClosedByItsBoardsOwnBeat() {
        Clearings.register(ABLE);
        PartyBoard board = new PartyBoard(
                dev.luizloyola.anima.core.social.PartyId.of(java.util.UUID.randomUUID()));
        ClearArea project = new ClearArea(ABLE, oneSlice(), 0.5);
        board.post(project);
        board.tick(0L);
        project.completed(project.open().get(0), new BoardBrainContext());
        assertTrue(project.finished());

        board.tick(1L);
        assertTrue(board.isEmpty(), "a satisfied project is dropped by the host's beat");
    }
}
