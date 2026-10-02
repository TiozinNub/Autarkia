package dev.luizloyola.autarkia.core.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.history.Slot;
import dev.luizloyola.anima.core.brain.history.Deed;
import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.board.SiteClaims;
import dev.luizloyola.anima.core.brain.board.WorkItem;
import dev.luizloyola.anima.core.brain.knowledge.CoverageGrid;
import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.brain.knowledge.Region;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.inv.ItemStack;
import dev.luizloyola.anima.core.brain.task.Idle;
import dev.luizloyola.anima.core.brain.task.PutAwaySurplus;
import dev.luizloyola.anima.core.brain.task.SweepingErrand;
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
class FellTreesTest {

    /** A kind of place that is only ever a test's. */
    private static final PoiKind THING = PoiKind.register("clear_test_thing", 1, "");

    /** A clearing that can survey and whose tasks do nothing — the rules are the subject here. */
    /** What the last survey the double handed out was told — the coverage seam, observed. */
    private static java.util.Map<Pos, Integer> lastKnown = java.util.Map.of();
    private static dev.luizloyola.anima.core.brain.knowledge.Coverage lastCoverage =
            dev.luizloyola.anima.core.brain.knowledge.Coverage.NONE;

    private record TestFelling(boolean surveys) implements Felling {
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

        @Override
        public java.util.List<Pos> residue(Region area,
                dev.luizloyola.anima.core.brain.knowledge.BlockProbe probe) {
            return java.util.List.copyOf(residue);
        }
    }

    /** Ground the double reports as residue — what perception will not name but a box must take. */
    private static java.util.List<Pos> residue = java.util.List.of();

    private static final Felling ABLE = new TestFelling(true);
    private static final Felling UNABLE = new TestFelling(false);

    /** A box exactly one slice across, so sweeping the whole box is a single errand. */
    private static Region oneSlice() {
        return new Region(new Pos(0, 60, 0), new Pos(10, 70, 10));
    }

    /** Two slices at {@link FellTrees#SLICE_SIZE}, so one can be swept while the other is not. */
    private static Region twoSlices() {
        return new Region(new Pos(0, 60, 0), new Pos(95, 70, 47));
    }

    private static FellTrees posted(Felling clearing, Region bounds) {
        FellTrees project = new FellTrees(clearing, bounds, 0.5);
        project.tick(0L);
        return project;
    }

    /** The survey errand on offer — position in the offer is not part of any rule here. */
    private static WorkItem surveyItem(FellTrees project) {
        return project.open().stream()
                .filter(item -> item.describe().startsWith("survey")).findFirst().orElseThrow();
    }

    /** The clear errand on offer, when exactly one is. */
    private static WorkItem clearItem(FellTrees project) {
        return project.open().stream()
                .filter(item -> item.describe().startsWith("fell")).findFirst().orElseThrow();
    }

    // ── who an item is offerable to ─────────────────────────────────────────────────────────

    /**
     * The regression that would hurt most: a survey or a tree stays open to whoever gets there
     * first — exactly what a claim-board is for. Only the bring-in, being about one member's load,
     * picks its taker.
     */
    @Test
    void anItemIsOfferableToAnybodyByDefault() {
        FellTrees project = posted(ABLE, oneSlice());
        WorkItem item = surveyItem(project);

        assertTrue(project.offerableTo(item, AgentId.random(), new BoardBrainContext()));
        assertTrue(project.offerableTo(item, AgentId.random(), new BoardBrainContext()),
                "a claim-board by design — Project's default hook says nothing about who an item "
                        + "is for");
    }

    // ── where the wood goes ──────────────────────────────────────────────────────────────────

    @Test
    void aBoxWithoutAHaulIsExactlyWhatItWas() {
        FellTrees project = posted(ABLE, oneSlice());

        assertFalse(project.home(), "no haul asked for, none invented");
        assertFalse(project.describe().contains("home"), "and the readout says nothing about one");
    }

    @Test
    void aHaulHomeIsCarriedAndSaidOutLoud() {
        FellTrees project = new FellTrees(ABLE, oneSlice(), 0.5, true);
        project.tick(0L);

        assertTrue(project.home());
        assertTrue(project.describe().contains("home"),
                "an operator who asked for the wood to go home should see it in the readout");
    }

    @Test
    void theHaulSurvivesASnapshotRoundTrip() {
        FellTrees project = new FellTrees(ABLE, oneSlice(), 0.5, true);
        project.tick(0L);

        FellTrees restored = FellTrees.restore(project.snapshot(), 0L).orElseThrow();

        assertTrue(restored.home());
    }

    @Test
    void aBoxWithoutAHaulRestoresWithoutOne() {
        FellTrees plain = posted(ABLE, oneSlice());

        FellTrees restored = FellTrees.restore(plain.snapshot(), 0L).orElseThrow();

        assertFalse(restored.home(), "no haul, no migration, no surprise chest");
    }

    @Test
    void aClearItemHaulsOnlyWhenTheWoodGoesHome() {
        FellTrees plain = posted(ABLE, oneSlice());
        plain.completed(plain.open().get(0), ctxThatSaw(new Pos(3, 60, 3)));
        Task withoutHaul = ((SweepingErrand) plain.open().stream()
                .filter(item -> item.describe().startsWith("fell")).findFirst().orElseThrow()
                .root()).work();

        assertFalse(withoutHaul instanceof HaulingErrand,
                "no haul, and the root is byte-for-byte what it always was");

        FellTrees hauled = new FellTrees(ABLE, oneSlice(), 0.5, true);
        hauled.tick(0L);
        hauled.completed(hauled.open().get(0), ctxThatSaw(new Pos(3, 60, 3)));
        Task hauling = ((SweepingErrand) hauled.open().stream()
                .filter(item -> item.describe().startsWith("fell")).findFirst().orElseThrow()
                .root()).work();

        assertTrue(hauling instanceof HaulingErrand,
                "with one, felling is followed by taking the load home when laden");
    }

    /** The haul home a clear item's errand ends with, as the executor reaches it. */
    private static PutAwaySurplus haulOf(WorkItem tree, BoardBrainContext ctx) {
        HaulingErrand errand = (HaulingErrand) ((SweepingErrand) tree.root()).work();
        return (PutAwaySurplus) errand.methods().get(0).decompose(ctx).get(1);
    }

    @Test
    void theLastTreeTakesTheWholeLoadHome() {
        FellTrees project = new FellTrees(ABLE, oneSlice(), 0.5, true);
        project.tick(0L);
        BoardBrainContext ctx = ctxThatSaw(new Pos(3, 60, 3));
        ctx.remember(THING, new Pos(8, 60, 8));
        project.completed(project.open().get(0), ctx);
        List<WorkItem> trees = project.open().stream()
                .filter(item -> item.describe().startsWith("fell")).toList();
        assertEquals(2, trees.size());
        ctx.inventory().set(0, ItemStack.of("minecraft:oak_log", 6, 64));

        project.claimed(trees.get(0));
        PutAwaySurplus haul = haulOf(trees.get(0), ctx);
        assertTrue(haul.satisfied(ctx), "a tree still unheld: six logs wait for the line");

        project.claimed(trees.get(1));
        assertFalse(haul.satisfied(ctx),
                "asked after the tree, not at the claim: somebody took the last one meanwhile");
    }

    private static boolean isBringIn(WorkItem item) {
        return item.describe().startsWith("bring");
    }

    /**
     * A gatherer's felling holds its tree outside the board. Offered anyway, the clearer failed on
     * the first tick and the failure counted against a tree with nothing wrong with it.
     */
    @Test
    void aTargetSomebodyElseIsRemovingWaitsUntilTheyLetGo() {
        FellTrees project = posted(ABLE, oneSlice());
        project.completed(surveyItem(project), ctxThatSaw(new Pos(3, 60, 3)));
        WorkItem tree = clearItem(project);
        SiteClaims sites = new SiteClaims();
        AgentId gatherer = AgentId.random();
        AgentId clearer = AgentId.random();
        BoardBrainContext asking = new BoardBrainContext();
        asking.claims = sites.forPerson(clearer);

        sites.claim(THING, new Pos(3, 60, 3), gatherer, asking.now());
        assertFalse(project.offerableTo(tree, clearer, asking));

        sites.release(THING, new Pos(3, 60, 3), gatherer);
        assertTrue(project.offerableTo(tree, clearer, asking));
    }

    @Test
    void aCrewMemberTheLastTreesWentPastIsSentHomeWithTheLoad() {
        FellTrees project = new FellTrees(ABLE, oneSlice(), 0.5, true);
        project.tick(0L);
        AgentId surveyor = AgentId.random();
        AgentId feller = AgentId.random();
        WorkItem survey = project.open().get(0);
        project.claimed(survey, surveyor);
        project.completed(survey, ctxThatSaw(new Pos(3, 60, 3)));
        WorkItem tree = clearItem(project);
        assertTrue(project.open().stream().noneMatch(FellTreesTest::isBringIn),
                "a tree nobody holds is still the next job");

        project.claimed(tree, feller);
        WorkItem offer = project.open().stream().filter(FellTreesTest::isBringIn)
                .findFirst().orElseThrow();
        BoardBrainContext laden = new BoardBrainContext();
        laden.inventory().set(0, ItemStack.of("minecraft:oak_log", 6, 64));
        assertTrue(project.offerableTo(offer, surveyor, laden));
        assertFalse(project.offerableTo(offer, AgentId.random(), laden),
                "the crew's loads, not anybody who happens to ask");
        assertFalse(project.offerableTo(offer, surveyor, new BoardBrainContext()),
                "an empty pack has nothing to bring");

        WorkItem mine = project.realise(offer, surveyor, laden);
        project.claimed(mine, surveyor);
        assertFalse(project.offerableTo(mine, feller, laden), "a load is its carrier's");
        PutAwaySurplus haul = (PutAwaySurplus) mine.root();
        assertEquals(0, haul.haulLine(), "everything goes, whatever the load");

        project.completed(tree, new BoardBrainContext());
        assertFalse(project.finished(), "closing now would drop the load on its way in");
        project.completed(mine, laden);
        assertTrue(project.finished());
    }

    @Test
    void aClearErrandBanksTheGroundItCrosses() {
        FellTrees project = posted(ABLE, oneSlice());
        BoardBrainContext ctx = ctxThatSaw(new Pos(3, 60, 3));
        project.completed(project.open().get(0), ctx);
        WorkItem tree = project.open().stream()
                .filter(i -> i.describe().startsWith("fell")).findFirst().orElseThrow();

        Task root = tree.root();

        assertTrue(root instanceof SweepingErrand, "a chopper's walk is evidence about the box");
        assertSame(project.coverage(), ((SweepingErrand) root).coverage());
    }

    @Test
    void theSweepWrapsTheHaulSoTheWalkHomeCountsToo() {
        FellTrees project = new FellTrees(ABLE, oneSlice(), 0.5, true);
        project.tick(0L);
        BoardBrainContext ctx = ctxThatSaw(new Pos(3, 60, 3));
        project.completed(project.open().get(0), ctx);
        WorkItem tree = project.open().stream()
                .filter(i -> i.describe().startsWith("fell")).findFirst().orElseThrow();

        Task root = tree.root();

        assertTrue(root instanceof SweepingErrand);
        assertTrue(((SweepingErrand) root).work() instanceof HaulingErrand,
                "3a is untouched: the haul is still a second step inside the errand");
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
        FellTrees project = posted(ABLE, twoSlices());
        BoardBrainContext ctx = new BoardBrainContext();
        Pos felled = new Pos(3, 60, 3);
        Pos stubborn = new Pos(11, 60, 11);
        ctx.remember(THING, felled);
        ctx.remember(THING, stubborn);
        project.completed(surveyItem(project), ctx);
        project.completed(itemAt(project, felled), ctx);
        for (int attempt = 0; attempt < FellTrees.REFUSE_AFTER; attempt++) {
            project.failed(itemAt(project, stubborn), ctx);
            ctx.advance(FellTrees.cooldownAfter(attempt + 1));
            project.tick(ctx.now());
        }

        // The second sweep still remembers all of it, and finds one more.
        Pos fresh = new Pos(60, 60, 20);
        ctx.remember(THING, fresh);
        project.completed(surveyItem(project), ctx);

        assertEquals(FellTrees.TargetState.CLEARED, project.ledger().get(felled).state(),
                "a stale memory of something already felled is not evidence it is back");
        assertEquals(FellTrees.TargetState.REFUSED, project.ledger().get(stubborn).state(),
                "and re-reporting a refusal would restart the loop REFUSE_AFTER exists to end");
        assertEquals(FellTrees.TargetState.OPEN, project.ledger().get(fresh).state());
    }

    @Test
    void aFailedTargetSitsOutLongerEachTime() {
        FellTrees project = posted(ABLE, oneSlice());
        BoardBrainContext ctx = new BoardBrainContext();
        Pos tree = new Pos(3, 60, 3);
        ctx.remember(THING, tree);
        project.completed(project.open().get(0), ctx);
        AgentId alone = AgentId.random();

        for (int failures = 1; failures <= 3; failures++) {
            long before = ctx.now();
            project.failed(itemAt(project, tree), alone, ctx);
            assertEquals(before + FellTrees.cooldownAfter(failures),
                    project.ledger().get(tree).retryAfter(), "after failure " + failures);
            ctx.advance(FellTrees.cooldownAfter(failures));
            project.tick(ctx.now());
        }
        assertEquals(FellTrees.FAIL_COOLDOWN, FellTrees.cooldownAfter(1));
        assertEquals(FellTrees.FAIL_COOLDOWN * 4, FellTrees.cooldownAfter(3));
        assertEquals(FellTrees.FAIL_COOLDOWN * 64, FellTrees.cooldownAfter(20), "capped");
        assertEquals(FellTrees.TargetState.OPEN, project.ledger().get(tree).state(),
                "still one person's word: never refused for it");
    }

    // ── residue: what perception will not name ─────────────────────────────────────────────

    @Test
    void aBoxWillNotCloseOverGroundHoldingResiduePerceptionCannotName() {
        // A felled tree can strip a fused neighbour's canopy, and TreeRule deliberately refuses to
        // call a crownless trunk a tree ("a woodpile, a stump"), so the stub is never remembered.
        // The sweep is honest — that ground WAS covered — which is exactly why the box must ask.
        residue = java.util.List.of(new Pos(3, 60, 3));
        FellTrees project = posted(ABLE, oneSlice());
        BoardBrainContext ctx = new BoardBrainContext();

        project.completed(project.open().get(0), ctx);

        assertFalse(project.finished(),
                "the ledger was empty and the box fully swept, but a stub still stands in it");
        assertEquals(FellTrees.TargetState.OPEN, project.ledger().get(new Pos(3, 60, 3)).state(),
                "and it is on offer, not merely counted");
        residue = java.util.List.of();
    }

    @Test
    void residueOutsideTheBoxIsNotTheBoxsToTake() {
        residue = java.util.List.of(new Pos(500, 60, 500));
        FellTrees project = posted(ABLE, oneSlice());
        BoardBrainContext ctx = new BoardBrainContext();

        project.completed(project.open().get(0), ctx);

        assertTrue(project.finished(), "a stub beyond the edges is somebody else's box");
        assertTrue(project.ledger().isEmpty());
        residue = java.util.List.of();
    }

    @Test
    void aBoxWithNoResidueClosesExactlyAsBefore() {
        residue = java.util.List.of();
        FellTrees project = posted(ABLE, oneSlice());
        BoardBrainContext ctx = new BoardBrainContext();

        project.completed(project.open().get(0), ctx);

        assertTrue(project.finished(), "the residue hook must cost a clean box nothing");
    }

    // ── what a report may say ────────────────────────────────────────────────────────────────

    @Test
    void aChopperReportsTheTreesTheyWalkedPast() {
        FellTrees project = posted(ABLE, oneSlice());
        BoardBrainContext ctx = ctxThatSaw(new Pos(3, 60, 3));
        project.completed(project.open().get(0), ctx);
        WorkItem tree = project.open().stream()
                .filter(i -> i.describe().startsWith("fell")).findFirst().orElseThrow();
        // On the way to the tree the chopper individuates another one.
        ctx.remember(THING, new Pos(8, 60, 8));

        project.completed(tree, ctx);

        assertTrue(project.ledger().containsKey(new Pos(8, 60, 8)),
                "a tree spotted mid-chop used to wait for a verify pass to re-walk that ground");
        assertFalse(project.finished(), "and it is on offer now, not a cycle later");
    }

    @Test
    void aChoppersReportCannotResurrectAnAnchorSomebodyAlreadyCleared() {
        // The 197/186 cycle: a felled tree lingers in its chopper's memory until the near field
        // re-probes that column, and a report that rewrote rows would put it straight back.
        FellTrees project = posted(ABLE, oneSlice());
        BoardBrainContext ctx = new BoardBrainContext();
        Pos felled = new Pos(3, 60, 3);
        Pos other = new Pos(8, 60, 8);
        ctx.remember(THING, felled);
        ctx.remember(THING, other);
        project.completed(project.open().get(0), ctx);
        project.completed(itemAt(project, felled), ctx);

        // A different worker finishes a different tree, still remembering the felled one.
        project.completed(itemAt(project, other), ctx);

        assertEquals(FellTrees.TargetState.CLEARED, project.ledger().get(felled).state(),
                "settle() only ever touches the item's own anchor, so this row is harvest's to "
                        + "leave alone — and it does, because the rule is membership, not state");
    }

    @Test
    void aStaleMemoryOfSomethingNobodyHasFelledIsStillWorthBanking() {
        BoardBrainContext ctx = new BoardBrainContext();
        ctx.advance(5_000L);
        ctx.rememberSeenAt(THING, new Pos(3, 60, 3), 500L);
        FellTrees project = new FellTrees(ABLE, oneSlice(), 0.5);
        project.tick(5_000L);

        project.completed(project.open().get(0), ctx);

        assertEquals(1, project.ledger().size(),
                "nobody has cleared it, so it is probably still standing — and a chop that finds "
                        + "nothing SUCCEEDS, so being wrong costs one short walk");
    }

    @Test
    void aWorkerWhoFailedStillReportsWhatTheySaw() {
        FellTrees project = posted(ABLE, oneSlice());
        BoardBrainContext ctx = ctxThatSaw(new Pos(3, 60, 3));
        project.completed(project.open().get(0), ctx);
        WorkItem tree = project.open().stream()
                .filter(i -> i.describe().startsWith("fell")).findFirst().orElseThrow();
        ctx.remember(THING, new Pos(8, 60, 8));

        project.failed(tree, AgentId.random(), ctx);

        assertTrue(project.ledger().containsKey(new Pos(8, 60, 8)),
                "they walked there and their near field ran; the errand's outcome is a different fact");
    }

    // ── the coverage the box keeps ───────────────────────────────────────────────────────────

    @Test
    void aPreemptedSurveyResumesWhereItStopped() {
        FellTrees project = posted(ABLE, oneSlice());
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
        FellTrees project = posted(ABLE, oneSlice());
        project.open().get(0).root();
        lastCoverage.settled(new Pos(0, 60, 0));

        FellTrees restored = FellTrees.restore(project.snapshot(), 0L).orElseThrow();
        restored.open().get(0).root();

        assertTrue(lastKnown.keySet().contains(new Pos(0, 60, 0)),
                "and it survives a restart, which is the half a reload used to lose");
    }

    /**
     * The headline path, end to end: a chopper's near field is the only thing that ever touches this
     * box, and the box must not close until that near field has actually been over all of it.
     *
     * <p>Two cells side by side. A body in the middle of the first banks its own cell whole and
     * eight of the second's sixteen squares — ground whose far edge it is eleven blocks from, which
     * is three past what it can individuate. A tree standing in that strip is exactly the failure
     * this feature must never have, so the second cell stays on the frontier until somebody walks
     * into it.
     */
    @Test
    void aBoxWalkedByAChopperClosesOnlyOnceEveryCellIsCovered() {
        // Aligned to the coverage grid, so "the cell beside" is a cell of this box.
        FellTrees project = posted(ABLE, new Region(new Pos(0, 60, 0), new Pos(15, 70, 7)));
        dev.luizloyola.anima.core.brain.knowledge.Coverage walking = project.coverage();

        walking.near(new Pos(4, 60, 4), 8);
        project.tick(1L);

        assertEquals(1, project.covered().settledCount(),
                "the cell underfoot, and only that one — spill into the next is not a walk into it");
        assertFalse(project.finished(),
                "half a cell of near-field spill closed this box before 2026-08-23, with the far "
                        + "strip of the second cell never individuated by anybody");
        assertFalse(project.open().isEmpty(), "so the slice is still on offer");

        walking.near(new Pos(12, 60, 4), 8);
        project.tick(2L);

        assertEquals(2, project.covered().settledCount());
        assertTrue(project.finished(),
                "and once every cell really has been walked, nothing holds the box open");
    }

    // ── the frontier is the offer ────────────────────────────────────────────────────────────

    @Test
    void aFreshBoxOffersOnlySurveys() {
        FellTrees project = posted(ABLE, twoSlices());

        assertEquals(2, project.open().size());
        assertTrue(project.open().stream().allMatch(item -> item.describe().startsWith("survey")));
    }

    @Test
    void aTreeReportedMidSweepIsOfferedWhileTheRestIsStillBeingWalked() {
        FellTrees project = posted(ABLE, twoSlices());
        BoardBrainContext ctx = ctxThatSaw(new Pos(3, 60, 3));

        project.completed(project.open().get(0), ctx);

        assertTrue(project.open().stream().anyMatch(i -> i.describe().startsWith("fell")),
                "the SURVEYING barrier is gone: the first slice reported puts trees in front of "
                        + "the crew while the rest of the box is still unwalked");
        assertTrue(project.open().stream().anyMatch(i -> i.describe().startsWith("survey")),
                "and the unswept slice is still on offer beside it");
    }

    @Test
    void aSliceEverybodyHasAlreadyCoveredIsNeverMintedAsAnErrand() {
        FellTrees project = posted(ABLE, twoSlices());
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
        FellTrees project = posted(ABLE, oneSlice());
        BoardBrainContext ctx = new BoardBrainContext();

        project.completed(project.open().get(0), ctx);

        assertTrue(project.finished(), "nothing found and nothing left unswept is done");
        assertTrue(project.open().isEmpty());
        assertTrue(project.describe().endsWith("done — nothing standing"),
                "not '0 cleared', which reads as trees missed: " + project.describe());
    }

    @Test
    void theBoxDoesNotCloseWhileAnyTargetIsStillOpen() {
        FellTrees project = posted(ABLE, oneSlice());
        BoardBrainContext ctx = ctxThatSaw(new Pos(3, 60, 3));

        project.completed(project.open().get(0), ctx);

        assertFalse(project.finished(), "a tree is standing in it");
    }

    @Test
    void aTargetCoolingOffKeepsTheBoxOpen() {
        FellTrees project = posted(ABLE, oneSlice());
        BoardBrainContext ctx = ctxThatSaw(new Pos(3, 60, 3));
        project.completed(project.open().get(0), ctx);
        WorkItem tree = clearItem(project);

        project.failed(tree, AgentId.random(), ctx);

        assertFalse(project.finished(),
                "OPEN and waiting out a cooldown is still OPEN — the box is not clear");
    }

    @Test
    void thereIsNoSecondSweepOfGroundSomebodyAlreadyCovered() {
        FellTrees project = posted(ABLE, oneSlice());
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
        FellTrees project = posted(ABLE, big);
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
        FellTrees project = posted(ABLE,
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
        FellTrees project =
                posted(ABLE, new Region(new Pos(0, 60, 0), new Pos(span - 1, 70, span - 1)));
        for (Region slice : project.slices()) {
            int wide = slice.max().x() - slice.min().x() + 1;
            int deep = slice.max().z() - slice.min().z() + 1;
            assertTrue(wide > 0 && wide <= FellTrees.SLICE_SIZE, "span " + span + " wide: " + wide);
            assertTrue(deep > 0 && deep <= FellTrees.SLICE_SIZE, "span " + span + " deep: " + deep);
            assertEquals(0, slice.min().x() % CoverageGrid.CELL, "span " + span + " x corner");
            assertEquals(0, slice.min().z() % CoverageGrid.CELL, "span " + span + " z corner");
        }
    }

    @Test
    void theSlicesTileTheBoxWithNoGapNoOverlapAndNothingOversized() {
        Region box = new Region(new Pos(-7, 60, 12), new Pos(123, 70, 60));
        FellTrees project = posted(ABLE, box);
        int covered = 0;
        for (Region slice : project.slices()) {
            int wide = slice.max().x() - slice.min().x() + 1;
            int deep = slice.max().z() - slice.min().z() + 1;
            assertTrue(wide <= FellTrees.SLICE_SIZE && deep <= FellTrees.SLICE_SIZE,
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
        FellTrees project = posted(UNABLE, oneSlice());
        assertTrue(project.open().isEmpty());
        // And the readout says why, rather than looking like a project with nothing to do.
        assertTrue(project.describe().contains("nobody can survey"));
        assertFalse(project.finished());
    }

    // ── the loop ─────────────────────────────────────────────────────────────────────────────

    @Test
    void everythingFoundAndFelledClosesTheBox() {
        FellTrees project = posted(ABLE, oneSlice());
        BoardBrainContext ctx = new BoardBrainContext();
        ctx.remember(THING, new Pos(3, 60, 3));
        ctx.remember(THING, new Pos(7, 60, 8));

        project.completed(project.open().get(0), ctx);
        assertEquals(2, project.open().size(), "both are on offer the moment they are reported");

        for (WorkItem item : List.copyOf(project.open())) {
            project.completed(item, ctx);
        }

        assertEquals(FellTrees.Phase.DONE, project.phase());
        assertTrue(project.describe().contains("2 cleared"));
    }

    @Test
    void onlyWhatIsInsideTheBoxIsTaken() {
        FellTrees project = posted(ABLE, oneSlice());
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
        FellTrees project = posted(ABLE, oneSlice());
        BoardBrainContext ctx = new BoardBrainContext();
        Pos stubborn = new Pos(3, 60, 3);
        ctx.remember(THING, stubborn);
        project.completed(project.open().get(0), ctx);

        for (int attempt = 1; attempt <= FellTrees.REFUSE_AFTER; attempt++) {
            assertEquals(1, project.open().size(), "attempt " + attempt + " should be on offer");
            project.failed(project.open().get(0), ctx);
            if (attempt < FellTrees.REFUSE_AFTER) {
                // A failure is paced: nothing is on offer until the cooldown runs out, or an agent
                // would burn every attempt within a second of the first.
                assertTrue(project.open().isEmpty(), "attempt " + attempt + " must cool down");
            }
            ctx.advance(FellTrees.cooldownAfter(attempt + 1));
            project.tick(ctx.now());
        }
        assertEquals(FellTrees.TargetState.REFUSED, project.ledger().get(stubborn).state());
        // Refused means gone from the offer for good — this is what stops "repeat until done"
        // from repeating forever over one thing nobody can remove. The last failure leaves nothing
        // un-swept and nothing standing, so the box closes on that same call.
        assertTrue(project.open().isEmpty());
        assertEquals(FellTrees.Phase.DONE, project.phase());
        assertTrue(project.describe().contains("1 refused"));
    }

    @Test
    void aRefusedAnchorIsNotReportedBackEither() {
        // Two slices, so the box stays open on the frontier while the refusal settles and a later
        // sweep still has somewhere to report from.
        FellTrees project = posted(ABLE, twoSlices());
        BoardBrainContext ctx = new BoardBrainContext();
        Pos stubborn = new Pos(3, 60, 3);
        ctx.remember(THING, stubborn);
        project.completed(surveyItem(project), ctx);
        for (int attempt = 0; attempt < FellTrees.REFUSE_AFTER; attempt++) {
            project.failed(itemAt(project, stubborn), ctx);
            ctx.advance(FellTrees.cooldownAfter(attempt + 1));
            project.tick(ctx.now());
        }
        assertEquals(FellTrees.TargetState.REFUSED, project.ledger().get(stubborn).state());

        // The next sweep reports it again, because it is still standing there in plain sight.
        project.completed(surveyItem(project), ctx);

        assertEquals(FellTrees.TargetState.REFUSED, project.ledger().get(stubborn).state(),
                "a refused target reported afresh would restart the loop it exists to end");
        assertTrue(project.finished());
    }

    @Test
    void aRefusedTargetGetsAnotherGoOnceItsNeighboursAreDown() {
        // A thing can be unreachable BECAUSE of what surrounds it, so a box that removed something
        // has changed the world and earned the refused ones a retry before it closes.
        FellTrees project = posted(ABLE, oneSlice());
        BoardBrainContext ctx = new BoardBrainContext();
        Pos stubborn = new Pos(3, 60, 3);
        Pos easy = new Pos(8, 60, 8);
        ctx.remember(THING, stubborn);
        ctx.remember(THING, easy);
        project.completed(project.open().get(0), ctx);

        // Refuse one, fell the other.
        for (int attempt = 0; attempt < FellTrees.REFUSE_AFTER; attempt++) {
            project.failed(itemAt(project, stubborn), ctx);
            ctx.advance(FellTrees.cooldownAfter(attempt + 1));
            project.tick(ctx.now());
        }
        assertEquals(FellTrees.TargetState.REFUSED, project.ledger().get(stubborn).state());
        project.completed(itemAt(project, easy), ctx);

        assertFalse(project.finished(), "the box would have closed, so the refusal gets its retry");
        assertEquals(FellTrees.TargetState.OPEN, project.ledger().get(stubborn).state(),
                "the trigger is the close, not the end of a round — there are no rounds");
    }

    @Test
    void aBoxThatFelledNothingDoesNotReopenAndEnds() {
        // The termination guarantee. Reopening costs a felled tree; a box that felled none has
        // changed nothing, so retrying would loop forever — which is what REFUSE_AFTER is for.
        FellTrees project = posted(ABLE, oneSlice());
        BoardBrainContext ctx = new BoardBrainContext();
        Pos stubborn = new Pos(3, 60, 3);
        ctx.remember(THING, stubborn);
        project.completed(project.open().get(0), ctx);
        for (int attempt = 0; attempt < FellTrees.REFUSE_AFTER; attempt++) {
            project.failed(itemAt(project, stubborn), ctx);
            ctx.advance(FellTrees.cooldownAfter(attempt + 1));
            project.tick(ctx.now());
        }

        assertEquals(FellTrees.TargetState.REFUSED, project.ledger().get(stubborn).state());
        assertEquals(FellTrees.Phase.DONE, project.phase(), "nothing changed, so nothing to retry");
        assertTrue(project.describe().contains("1 refused"),
                "the operator can see what stopped it rather than inferring it");
    }

    @Test
    void refusalsAreReopenedOnceAndOnlyOnce() {
        // The reopening is paid for out of felled targets, and the payment is spent. A second close
        // with nothing felled since must end the box rather than hand out another free retry.
        FellTrees project = posted(ABLE, oneSlice());
        BoardBrainContext ctx = new BoardBrainContext();
        Pos stubborn = new Pos(3, 60, 3);
        Pos easy = new Pos(8, 60, 8);
        ctx.remember(THING, stubborn);
        ctx.remember(THING, easy);
        project.completed(project.open().get(0), ctx);
        for (int attempt = 0; attempt < FellTrees.REFUSE_AFTER; attempt++) {
            project.failed(itemAt(project, stubborn), ctx);
            ctx.advance(FellTrees.cooldownAfter(attempt + 1));
            project.tick(ctx.now());
        }
        project.completed(itemAt(project, easy), ctx); // felling buys the one retry

        for (int attempt = 0; attempt < FellTrees.REFUSE_AFTER; attempt++) {
            project.failed(itemAt(project, stubborn), ctx);
            ctx.advance(FellTrees.cooldownAfter(attempt + 1));
            project.tick(ctx.now());
        }

        assertTrue(project.finished(), "the licence was spent; a box cannot retry on credit");
        assertEquals(FellTrees.TargetState.REFUSED, project.ledger().get(stubborn).state());
    }

    /** The open item standing at this anchor — the tests act through the board's own offers. */
    private static WorkItem itemAt(FellTrees project, Pos anchor) {
        return project.itemFor(new WorkKey.AtPlace(WorkKey.CLEAR, anchor)).orElseThrow();
    }

    @Test
    void oneWorkerFailingOverAndOverCannotCondemnATree() {
        // The 134-tree bug: one body in a try-fail loop refused a whole ledger, because failures
        // were charged to the tree rather than the worker. Giving up means several DIFFERENT people
        // could not.
        FellTrees project = posted(ABLE, oneSlice());
        BoardBrainContext ctx = new BoardBrainContext();
        Pos tree = new Pos(3, 60, 3);
        ctx.remember(THING, tree);
        project.completed(project.open().get(0), ctx);

        AgentId stuck = AgentId.random();
        for (int attempt = 1; attempt <= FellTrees.REFUSE_AFTER * 3; attempt++) {
            project.failed(itemAt(project, tree), stuck, ctx);
            ctx.advance(FellTrees.cooldownAfter(attempt));
            project.tick(ctx.now());
        }
        assertEquals(FellTrees.TargetState.OPEN, project.ledger().get(tree).state(),
                "one body failing repeatedly is evidence about the body, not about the tree");
    }

    @Test
    void enoughDifferentPeopleFailingIsWhatRefusesIt() {
        FellTrees project = posted(ABLE, oneSlice());
        BoardBrainContext ctx = new BoardBrainContext();
        Pos tree = new Pos(3, 60, 3);
        ctx.remember(THING, tree);
        project.completed(project.open().get(0), ctx);

        for (int attempt = 0; attempt < FellTrees.REFUSE_AFTER; attempt++) {
            project.failed(itemAt(project, tree), AgentId.random(), ctx);
            ctx.advance(FellTrees.cooldownAfter(attempt + 1));
            project.tick(ctx.now());
        }
        assertEquals(FellTrees.TargetState.REFUSED, project.ledger().get(tree).state());
    }

    @Test
    void aFailedSliceIsOfferedAgainAfterItsCooldown() {
        FellTrees project = posted(ABLE, oneSlice());
        BoardBrainContext ctx = new BoardBrainContext();
        project.failed(project.open().get(0), ctx);
        assertTrue(project.open().isEmpty());
        ctx.advance(FellTrees.FAIL_COOLDOWN);
        project.tick(ctx.now());
        assertEquals(1, project.open().size());
        assertFalse(project.finished(), "un-swept ground holds the box open however it got there");
    }

    // ── holds ────────────────────────────────────────────────────────────────────────────────

    @Test
    void anErrandSomebodyHoldsIsNeverWithdrawn() {
        FellTrees project = posted(ABLE, oneSlice());
        BoardBrainContext ctx = new BoardBrainContext();
        WorkItem taken = project.open().get(0);
        project.claimed(taken);
        project.failed(taken, ctx);
        // Failing releases it; the guard is about the OTHER route — a project deciding on its own
        // beat that it no longer wants an errand a worker is walking to.
        WorkItem again = project.open().isEmpty() ? null : project.open().get(0);
        assertTrue(again == null || again != taken);

        ctx.advance(FellTrees.FAIL_COOLDOWN);
        project.tick(ctx.now());
        WorkItem reoffered = project.open().get(0);
        project.claimed(reoffered);
        project.tick(ctx.now() + 1);
        assertSame(reoffered, project.open().get(0), "a held item must survive a beat unchanged");
    }

    @Test
    void anItemKeepsItsIdentityAcrossBeats() {
        // The board leases by IDENTITY, so re-minting on every ask would drop every hold.
        FellTrees project = posted(ABLE, oneSlice());
        WorkItem first = project.open().get(0);
        project.tick(1L);
        project.tick(2L);
        assertSame(first, project.open().get(0));
    }

    // ── durable names ────────────────────────────────────────────────────────────────────────

    @Test
    void everyOfferedItemHasADurableNameThatFindsItAgain() {
        FellTrees project = posted(ABLE, oneSlice());
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
        FellTrees project = posted(ABLE, oneSlice());
        assertTrue(project.itemFor(new WorkKey.AtPlace(WorkKey.CLEAR, new Pos(1, 1, 1))).isEmpty());
    }

    // ── continuity ───────────────────────────────────────────────────────────────────────────

    @Test
    void aSavedProjectComesBackMidClearWithTheSameLedgerAndOffers() {
        Fellings.register(ABLE);
        FellTrees project = posted(ABLE, oneSlice());
        BoardBrainContext ctx = new BoardBrainContext();
        ctx.remember(THING, new Pos(3, 60, 3));
        ctx.remember(THING, new Pos(7, 60, 8));
        project.completed(project.open().get(0), ctx);
        project.completed(project.open().get(0), ctx);

        FellTrees back = FellTrees.restore(project.snapshot(), ctx.now()).orElseThrow();
        assertEquals(FellTrees.Phase.WORKING, back.phase());
        assertEquals(project.ledger(), back.ledger());
        assertEquals(1, back.open().size(), "the one target still standing is on offer again");
        assertEquals(project.describe(), back.describe());
    }

    @Test
    void aSavedProjectRemembersWhatItGaveUpOn() {
        Fellings.register(ABLE);
        FellTrees project = posted(ABLE, oneSlice());
        BoardBrainContext ctx = new BoardBrainContext();
        Pos stubborn = new Pos(3, 60, 3);
        ctx.remember(THING, stubborn);
        project.completed(project.open().get(0), ctx);
        for (int attempt = 0; attempt < FellTrees.REFUSE_AFTER; attempt++) {
            project.failed(project.open().get(0), ctx);
            ctx.advance(FellTrees.cooldownAfter(attempt + 1));
            project.tick(ctx.now());
        }

        FellTrees back = FellTrees.restore(project.snapshot(), ctx.now()).orElseThrow();
        assertEquals(FellTrees.TargetState.REFUSED, back.ledger().get(stubborn).state(),
                "a restart that forgot a refusal would let the loop back in through the store");
    }

    @Test
    void aSavedProjectRemembersHowFarTheSurveyGot() {
        Fellings.register(ABLE);
        Region big = new Region(new Pos(0, 60, 0), new Pos(60, 70, 50));
        FellTrees project = posted(ABLE, big);
        BoardBrainContext ctx = new BoardBrainContext();
        project.completed(project.open().get(0), ctx);
        assertEquals(3, project.open().size());

        FellTrees back = FellTrees.restore(project.snapshot(), ctx.now()).orElseThrow();
        assertEquals(3, back.open().size(), "a walked slice must not be walked again");
        assertEquals(project.describe(), back.describe(),
                "and the swept fraction comes back where it left off, not at zero");
    }

    @Test
    void aProjectWhoseClearingThisBuildLacksComesBackAsNothing() {
        Fellings.clear();
        FellTrees project = posted(ABLE, oneSlice());
        // Never silently an empty project: the store's job is to refuse the world, and it can only
        // do that if this says so rather than handing back something plausible.
        assertTrue(FellTrees.restore(project.snapshot(), 0L).isEmpty());
        Fellings.register(ABLE);
    }

    @Test
    void aSurveyItemKeepsItsNameAcrossARestart() {
        Fellings.register(ABLE);
        Region big = new Region(new Pos(0, 60, 0), new Pos(60, 70, 50));
        FellTrees project = posted(ABLE, big);
        WorkItem held = project.open().get(1);
        WorkKey key = project.keyOf(held).orElseThrow();

        FellTrees back = FellTrees.restore(project.snapshot(), 0L).orElseThrow();
        // A different object, the same errand: the member walking to it gets THAT one back rather
        // than the pool.
        assertTrue(back.itemFor(key).isPresent());
        assertEquals(held.describe(), back.itemFor(key).orElseThrow().describe());
    }

    @Test
    void stateRoundTripsTheCoverageGrid() {
        Fellings.register(ABLE);
        FellTrees project = posted(ABLE, oneSlice());
        project.covered().markNear(new Pos(4, 60, 4), 8);
        int before = project.covered().settledCount();

        FellTrees back = FellTrees.restore(project.snapshot(), 0L).orElseThrow();

        assertEquals(before, back.covered().settledCount(),
                "a reload must not put a settler back on ground the party already walked");
    }

    @Test
    void aWorldSavedBeforeTheFrontierComesBackWorking() {
        assertEquals(FellTrees.Phase.WORKING, FellTrees.phaseByName("SURVEYING"));
        assertEquals(FellTrees.Phase.WORKING, FellTrees.phaseByName("CLEARING"));
        assertEquals(FellTrees.Phase.WORKING, FellTrees.phaseByName("VERIFYING"));
        assertEquals(FellTrees.Phase.DONE, FellTrees.phaseByName("DONE"));
    }

    // ── the board around it ──────────────────────────────────────────────────────────────────

    @Test
    void aPartyBoardHandsEveryHolderBackTheirOwnErrand() {
        Fellings.register(ABLE);
        // restore() now resolves the row's kind through PartyProjects before it ever asks a
        // Felling for anything — a mod's bootstrap does this once; a plain core test has to.
        PartyProjects.register(FellTrees.TYPE);
        PartyBoard board = new PartyBoard(
                dev.luizloyola.anima.core.social.PartyId.of(java.util.UUID.randomUUID()));
        Region big = new Region(new Pos(0, 60, 0), new Pos(60, 70, 50));
        FellTrees project = new FellTrees(ABLE, big, 0.5);
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

        FellTrees back = (FellTrees) reloaded.projects().get(0);
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
    void theBoardHandsALadenCrewMemberTheirOwnWayHomeAndKeepsItAcrossAReload() {
        Fellings.register(ABLE);
        PartyProjects.register(FellTrees.TYPE);
        PartyBoard board = new PartyBoard(
                dev.luizloyola.anima.core.social.PartyId.of(java.util.UUID.randomUUID()));
        FellTrees project = new FellTrees(ABLE, oneSlice(), 0.5, true);
        board.post(project);
        board.tick(0L);
        AgentId surveyor = AgentId.random();
        AgentId feller = AgentId.random();
        WorkItem survey = project.open().get(0);
        assertTrue(board.claim(survey, surveyor, 0L));
        board.completed(survey, surveyor, ctxThatSaw(new Pos(3, 60, 3)));
        assertTrue(board.claim(clearItem(project), feller, 0L), "the last tree, taken");
        BoardBrainContext laden = new BoardBrainContext();
        laden.inventory().set(0, ItemStack.of("minecraft:oak_log", 6, 64));

        assertTrue(board.bestFor(AgentId.random(), laden, 0L).isEmpty(),
                "nothing for somebody who never worked the box");
        WorkItem mine = board.bestFor(surveyor, laden, 0L).orElseThrow();
        assertTrue(isBringIn(mine));
        assertTrue(board.claim(mine, surveyor, 0L));
        WorkKey key = project.keyOf(mine).orElseThrow();
        assertEquals(new WorkKey.ForMember(WorkKey.BRING_IN, surveyor), key);

        List<PartyBoard.Row> saved = board.snapshot(0L);
        PartyBoard reloaded = new PartyBoard(board.party());
        assertEquals(0, reloaded.restore(saved, 0L));
        FellTrees back = (FellTrees) reloaded.projects().get(0);
        assertTrue(reloaded.holds(back.itemFor(key).orElseThrow(), surveyor, 0L),
                "the walk home survives a restart");
    }

    @Test
    void aFinishedProjectIsClosedByItsBoardsOwnBeat() {
        Fellings.register(ABLE);
        PartyBoard board = new PartyBoard(
                dev.luizloyola.anima.core.social.PartyId.of(java.util.UUID.randomUUID()));
        FellTrees project = new FellTrees(ABLE, oneSlice(), 0.5);
        board.post(project);
        board.tick(0L);
        project.completed(project.open().get(0), new BoardBrainContext());
        assertTrue(project.finished());

        board.tick(1L);
        assertTrue(board.isEmpty(), "a satisfied project is dropped by the host's beat");
    }

    @Test
    void itsErrandsTellOfSurveyingAndClearingTheirKind() {
        FellTrees project = posted(ABLE, oneSlice());
        Slot kind = Slot.lang("autarkia.clearing." + ABLE.id());
        assertEquals(Deed.of(WorkDoings.SURVEYING, kind), surveyItem(project).doing());

        project.completed(project.open().get(0), ctxThatSaw(new Pos(3, 60, 3)));
        assertEquals(Deed.of(WorkDoings.CLEARING, kind), clearItem(project).doing());
    }
}
