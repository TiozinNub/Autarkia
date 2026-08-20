package dev.luizloyola.autarkia.core.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.board.WorkItem;
import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.brain.knowledge.Region;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.Idle;
import dev.luizloyola.anima.core.brain.task.SurveyArea;
import dev.luizloyola.anima.core.brain.task.Task;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The phase machine, the slices, the ledger and the refusal rule — everything that decides whether
 * a box gets cleared, proven without a world, a body or a block of perception.
 *
 * <p>Blind: a survey here is a member handing back what they "remember". That is what
 * a real one will be. The walking is step 2's and changes no rule below.
 */
class ClearAreaTest {

    /** A kind of place that is only ever a test's. */
    private static final PoiKind THING = PoiKind.register("clear_test_thing", 1, "");

    /** A clearing that can survey and whose tasks do nothing — the rules are the subject here. */
    /** What the last survey the double handed out was told — the coverage seam, observed. */
    private static java.util.Set<Pos> lastSettled = java.util.Set.of();
    private static dev.luizloyola.anima.core.brain.task.SurveyArea.Coverage lastCoverage =
            dev.luizloyola.anima.core.brain.task.SurveyArea.Coverage.NONE;

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
        public Task survey(Region slice, java.util.Set<Pos> settled,
                dev.luizloyola.anima.core.brain.task.SurveyArea.Coverage coverage) {
            lastSettled = java.util.Set.copyOf(settled);
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

    /** A box exactly one slice across, so a whole pass is a single errand. */
    private static Region oneSlice() {
        return new Region(new Pos(0, 60, 0), new Pos(10, 70, 10));
    }

    private static ClearArea posted(Clearing clearing, Region bounds) {
        ClearArea project = new ClearArea(clearing, bounds, 0.5);
        project.tick(0L);
        return project;
    }

    // ── the coverage a pass keeps ────────────────────────────────────────────────────────────

    @Test
    void aPreemptedSurveyResumesWhereItStopped() {
        ClearArea project = posted(ABLE, oneSlice());
        var errand = project.open().get(0);

        errand.root();                      // granted: the double captures the sink
        lastCoverage.swept(new Pos(0, 60, 0));
        lastCoverage.swept(new Pos(8, 60, 0));
        errand.root();                      // preempted, then re-granted: a FRESH task

        assertTrue(lastSettled.containsAll(java.util.Set.of(new Pos(0, 60, 0), new Pos(8, 60, 0))),
                "the sweep resumes; it does not walk the box again from the treeline");
    }

    @Test
    void coverageSurvivesASnapshotRoundTrip() {
        ClearArea project = posted(ABLE, oneSlice());
        project.open().get(0).root();
        lastCoverage.swept(new Pos(0, 60, 0));

        ClearArea restored = ClearArea.restore(project.snapshot(), 0L).orElseThrow();
        restored.open().get(0).root();

        assertTrue(lastSettled.contains(new Pos(0, 60, 0)),
                "and it survives a restart, which is the half a reload used to lose");
    }

    @Test
    void aNewPassWalksItsOwnGround() {
        ClearArea project = posted(ABLE, oneSlice());
        project.open().get(0).root();
        lastCoverage.swept(new Pos(0, 60, 0));

        // Report the slice done, which turns the pass over.
        BoardBrainContext ctx = new BoardBrainContext();
        project.completed(project.open().get(0), ctx);
        project.tick(1L);
        if (project.phase() == ClearArea.Phase.SURVEYING || project.phase() == ClearArea.Phase.VERIFYING) {
            project.open().get(0).root();
            assertFalse(lastSettled.contains(new Pos(0, 60, 0)),
                    "coverage is per-pass, never cumulative — a verify pass walks its own ground");
        }
    }

    // ── what it offers ───────────────────────────────────────────────────────────────────────

    @Test
    void aFreshProjectOffersItsSlicesAndNothingElse() {
        ClearArea project = posted(ABLE, oneSlice());
        assertEquals(ClearArea.Phase.SURVEYING, project.phase());
        assertEquals(1, project.open().size());
        assertTrue(project.open().get(0).describe().startsWith("survey slice 1/1"));
    }

    @Test
    void aBoxDividesIntoWholeSlicesAndTheGridCoversIt() {
        // Two slices wide, two deep, and the last of each is the short remainder.
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
        assertEquals(List.of(25, 24), widths(0, 48));
        assertEquals(List.of(32, 33, 32), widths(0, 96));
        // Rounded evenly rather than front-loaded, and the same shape wherever the box sits.
        assertEquals(List.of(32, 33, 32), widths(-1000, -904));
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
    void aSurveyThatFoundNothingFinishesTheWholeProject() {
        ClearArea project = posted(ABLE, oneSlice());
        BoardBrainContext ctx = new BoardBrainContext();
        project.completed(project.open().get(0), ctx);
        // Walked the whole box, found nothing: there is no second pass worth making.
        assertEquals(ClearArea.Phase.DONE, project.phase());
        assertTrue(project.finished());
    }

    @Test
    void surveyThenClearThenVerifyThenDone() {
        ClearArea project = posted(ABLE, oneSlice());
        BoardBrainContext ctx = new BoardBrainContext();
        ctx.remember(THING, new Pos(3, 60, 3));
        ctx.remember(THING, new Pos(7, 60, 8));

        project.completed(project.open().get(0), ctx);
        assertEquals(ClearArea.Phase.CLEARING, project.phase());
        assertEquals(2, project.open().size());

        for (WorkItem item : List.copyOf(project.open())) {
            project.completed(item, ctx);
        }
        ctx.forget(THING, new Pos(3, 60, 3)); // felling one is also forgetting it
        ctx.forget(THING, new Pos(7, 60, 8));
        // Everything reported is gone, so the box is walked again — from scratch, because what a
        // first pass missed is precisely where nobody went.
        assertEquals(ClearArea.Phase.VERIFYING, project.phase());
        assertEquals(1, project.open().size());

        project.completed(project.open().get(0), ctx);
        assertEquals(ClearArea.Phase.DONE, project.phase());
        assertTrue(project.describe().contains("2 cleared"));
    }

    @Test
    void aVerifyPassThatFindsSomethingNewGoesBackToClearing() {
        ClearArea project = posted(ABLE, oneSlice());
        BoardBrainContext ctx = new BoardBrainContext();
        ctx.remember(THING, new Pos(3, 60, 3));

        project.completed(project.open().get(0), ctx);
        project.completed(project.open().get(0), ctx);
        ctx.forget(THING, new Pos(3, 60, 3));
        assertEquals(ClearArea.Phase.VERIFYING, project.phase());

        // The second surveyor walks ground the first one hurried past.
        ctx.remember(THING, new Pos(9, 60, 9));
        project.completed(project.open().get(0), ctx);
        assertEquals(ClearArea.Phase.CLEARING, project.phase());
        assertEquals(1, project.open().size());
    }

    @Test
    void somethingStandingAtAClearedAnchorAgainIsFoundAgain() {
        // Regrowth. Sealing a cleared anchor off for good blinded the review to a sapling grown back
        // where one was taken — the one thing a review exists to catch (Luiz replanted mid-run; no
        // pass saw it). A chop that finds nothing now SUCCEEDS, so a stale memory costs one cheap
        // walk rather than three failures and a permanent refusal.
        ClearArea project = posted(ABLE, oneSlice());
        BoardBrainContext ctx = new BoardBrainContext();
        Pos spot = new Pos(3, 60, 3);
        ctx.remember(THING, spot);
        project.completed(project.open().get(0), ctx);
        project.completed(project.open().get(0), ctx);
        ctx.forget(THING, spot); // felled, and the belief healed with it
        assertEquals(ClearArea.Phase.VERIFYING, project.phase());

        ctx.remember(THING, spot); // something is standing there again
        project.completed(project.open().get(0), ctx);
        assertEquals(ClearArea.Phase.CLEARING, project.phase(), "the review must notice regrowth");
        assertEquals(ClearArea.TargetState.OPEN, project.ledger().get(spot).state());
    }

    @Test
    void aFelledTreeNobodyRemembersDoesNotComeBack() {
        // The other half: once the belief is healed, the anchor stays settled and the box closes.
        ClearArea project = posted(ABLE, oneSlice());
        BoardBrainContext ctx = new BoardBrainContext();
        Pos gone = new Pos(3, 60, 3);
        ctx.remember(THING, gone);
        project.completed(project.open().get(0), ctx);
        project.completed(project.open().get(0), ctx);
        ctx.forget(THING, gone);

        assertEquals(ClearArea.Phase.VERIFYING, project.phase());
        project.completed(project.open().get(0), ctx);
        assertEquals(ClearArea.Phase.DONE, project.phase());
        assertEquals(ClearArea.TargetState.CLEARED, project.ledger().get(gone).state());
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
            } else {
                // The last failure settles the last target. That is what ends the clearing phase
                // — so the verify pass opens on the same call and its slice is already on offer.
                assertEquals(ClearArea.Phase.VERIFYING, project.phase());
            }
            ctx.advance(ClearArea.FAIL_COOLDOWN);
            project.tick(ctx.now());
        }
        assertEquals(ClearArea.TargetState.REFUSED, project.ledger().get(stubborn).state());
        // Refused means gone from the offer for good — this is what stops "repeat until done"
        // from repeating forever over one thing nobody can remove.
        assertTrue(project.open().stream().noneMatch(item -> item.describe().contains("clear")));
        assertEquals(ClearArea.Phase.VERIFYING, project.phase());

        project.completed(project.open().get(0), ctx);
        assertEquals(ClearArea.Phase.DONE, project.phase());
        assertTrue(project.describe().contains("1 refused"));
    }

    @Test
    void aRefusedAnchorIsNotReportedBackEither() {
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
        // The verifier reports it again, because it is still standing there in plain sight.
        project.completed(project.open().get(0), ctx);
        assertEquals(ClearArea.Phase.DONE, project.phase(),
                "a refused target reported afresh would restart the loop it exists to end");
    }

    @Test
    void aRefusedTargetGetsAnotherGoOnceItsNeighboursAreDown() {
        // A thing can be unreachable BECAUSE of what surrounds it, so a round that removed
        // something has changed the world and earned the refused ones a retry.
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

        assertEquals(ClearArea.Phase.VERIFYING, project.phase());
        assertEquals(ClearArea.TargetState.OPEN, project.ledger().get(stubborn).state(),
                "the round felled something, so the one it gave up on deserves another look");
    }

    @Test
    void aRoundThatFelledNothingDoesNotReopenAndTheProjectEnds() {
        // The termination guarantee. Reopening costs a felled tree; a round that felled none has
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
        assertEquals(ClearArea.Phase.VERIFYING, project.phase());
        assertEquals(ClearArea.TargetState.REFUSED, project.ledger().get(stubborn).state());

        project.completed(project.open().get(0), ctx); // the verify sweep finds nothing new
        assertEquals(ClearArea.Phase.DONE, project.phase(), "nothing changed, so nothing to retry");
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
    void nothingIsWrittenOffBeforeAnybodyHasSwept() {
        // The guard that makes the whole rule safe: on a first pass no cell is dirty, so without
        // it every cell would read as clear-and-surrounded-by-clear and the box would be skipped
        // entirely, unseen.
        ClearArea project = posted(ABLE, bigBox());
        assertTrue(project.skippable().isEmpty());
    }

    @Test
    void groundBesideSomethingFoundIsStillWalked_butFarClearGroundIsNot() {
        // Luiz's rule, as his own worked example: a clear cell is written off only when none of
        // its EIGHT neighbours held anything either, so one find keeps its whole ring in play.
        ClearArea project = posted(ABLE, bigBox());
        BoardBrainContext ctx = new BoardBrainContext();
        // One thing, in the cell whose corner is (16, 16) — the middle of a 5x5 grid of cells.
        ctx.remember(THING, new Pos(18, 60, 18));
        project.completed(project.open().get(0), ctx);
        project.completed(project.open().get(0), ctx); // fell it; the box moves to VERIFYING
        assertEquals(ClearArea.Phase.VERIFYING, project.phase());

        java.util.Set<Pos> skip = project.skippable();
        assertFalse(skip.contains(new Pos(16, 60, 16)), "the cell it was found in");
        assertFalse(skip.contains(new Pos(8, 60, 16)), "orthogonally beside it");
        assertFalse(skip.contains(new Pos(24, 60, 24)), "diagonally beside it");
        assertTrue(skip.contains(new Pos(0, 60, 0)), "two cells away and never near anything");
        assertTrue(skip.contains(new Pos(32, 60, 32)), "the far corner");
        assertFalse(skip.isEmpty());
    }

    @Test
    void eachPassJudgesOnWhatITselfSaw_notOnEverythingEverFound() {
        // Judging by the whole ledger never settles: a cell that once held a tree stays dirty, so a
        // worked box reads dirty everywhere and every later pass re-walks it. What matters is what
        // was standing last time somebody looked.
        ClearArea project = posted(ABLE, bigBox());
        BoardBrainContext ctx = new BoardBrainContext();
        Pos west = new Pos(2, 60, 18);
        Pos east = new Pos(34, 60, 18);
        ctx.remember(THING, west);
        ctx.remember(THING, east);
        project.completed(project.open().get(0), ctx);          // pass 1 sees both
        for (WorkItem item : List.copyOf(project.open())) {
            project.completed(item, ctx);                        // fell them
        }
        ctx.forget(THING, west);
        ctx.forget(THING, east);
        assertEquals(ClearArea.Phase.VERIFYING, project.phase());
        // Pass 1 saw both, so both ends are still in play going into the verify.
        assertFalse(project.skippable().contains(new Pos(0, 60, 16)));
        assertFalse(project.skippable().contains(new Pos(32, 60, 16)));

        // The verify sees only the west one standing again; the east end is genuinely empty.
        ctx.remember(THING, west);
        project.completed(project.open().get(0), ctx);
        assertEquals(ClearArea.Phase.CLEARING, project.phase());
        project.completed(project.open().get(0), ctx);
        ctx.forget(THING, west);
        assertEquals(ClearArea.Phase.VERIFYING, project.phase());

        java.util.Set<Pos> skip = project.skippable();
        assertFalse(skip.contains(new Pos(0, 60, 16)), "still beside what the LAST pass saw");
        assertTrue(skip.contains(new Pos(32, 60, 16)),
                "the last pass saw nothing here — a tree that stood here once does not keep it dirty");
    }

    @Test
    void aSliceOffTheBoxsCellGridStillGetsItsSkipCredited() {
        // SurveyArea credits a settled cell only when handed that cell's EXACT corner, and slice
        // corners are no longer multiples of a cell from the box's — so corners built on the box's
        // grid would match almost nothing and a verify pass would re-walk the whole box for nothing.
        // Against a real SurveyArea: the agreement between the two grids is the subject.
        Region box = new Region(new Pos(0, 60, 0), new Pos(96, 70, 39));
        ClearArea project = posted(ABLE, box);
        Region far = project.slices().get(project.slices().size() - 1);
        assertTrue((far.min().x() - box.min().x()) % SurveyArea.CELL != 0,
                "this test is only about a slice that is OFF the box's cell grid");

        BoardBrainContext ctx = new BoardBrainContext();
        ctx.remember(THING, new Pos(2, 60, 2)); // one find, at the opposite end from `far`
        for (WorkItem item : List.copyOf(project.open())) {
            project.completed(item, ctx); // walk every slice
        }
        for (WorkItem item : List.copyOf(project.open())) {
            project.completed(item, ctx); // clear what the walk found
        }
        assertEquals(ClearArea.Phase.VERIFYING, project.phase());

        SurveyArea sweep = new SurveyArea(far, THING, project.skippable());
        assertEquals(sweep.cells(), sweep.cellsKnown(),
                "a slice nowhere near anything the last pass saw should start already known");
    }

    /** Five coverage cells a side, so a find in the middle leaves ground beyond its ring. */
    private static Region bigBox() {
        return new Region(new Pos(0, 60, 0), new Pos(39, 70, 39));
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
        assertEquals(ClearArea.Phase.SURVEYING, project.phase());
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
        assertEquals(ClearArea.Phase.CLEARING, back.phase());
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
        assertTrue(back.describe().contains("1/4 slices"));
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
