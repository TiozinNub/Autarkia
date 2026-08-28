package dev.luizloyola.autarkia.core.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.board.WorkItem;
import dev.luizloyola.anima.core.brain.knowledge.AgentKnowledge;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.EnsureStore;
import dev.luizloyola.anima.core.brain.task.ObtainItem;
import dev.luizloyola.anima.core.brain.task.PutItems;
import dev.luizloyola.anima.core.brain.task.Task;
import dev.luizloyola.anima.core.inv.Inventory;
import dev.luizloyola.anima.core.inv.ItemCall;
import dev.luizloyola.anima.core.inv.ItemStack;
import dev.luizloyola.anima.core.log.Entry;
import dev.luizloyola.anima.core.social.PartyId;
import dev.luizloyola.anima.core.store.Store;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The ledger, the slate and the two edges — everything that decides whether a party's quota gets
 * filled, proven without a world, a body or a block of perception.
 *
 * <p>Blind, like {@code ClearAreaTest}: a deposit here is a member handing back what they
 * "remember" of a chest they just opened. That is exactly what a real one is — {@code PutItems}
 * writes the sighting as it moves the stacks, and the project reads it off their memory.
 *
 * <p>Nobody is in a party here, and that is the point: after 2026-08-28 a gather names no member
 * until one asks, so a roster is not something these tests can be run against.
 */
class GatherTest {

    private static final Pos YARD = new Pos(10, 64, 10);

    /** A block off the hint, because a yard is a hint and the chest lands where ground allows. */
    private static final Pos CHEST = new Pos(11, 64, 10);

    private static final PartyId PARTY = PartyId.of(UUID.randomUUID());

    private static final AgentId KYLE = AgentId.random();
    private static final AgentId SAM = AgentId.random();

    @BeforeEach
    void wireTheRegistries() {
        Splits.register(CarrySplit.INSTANCE);
        PartyProjects.register(Gather.TYPE);
    }

    @AfterEach
    void restoreTheRegistry() {
        // Restored here rather than at the end of the one test that clears it: an assertion that
        // fails mid-test would otherwise leave the registry empty for every class after this one.
        Splits.register(CarrySplit.INSTANCE);
    }

    private static Gather posted(int target) {
        Gather project = new Gather(Stock.LOGS, target, YARD, 0.5, PARTY, CarrySplit.INSTANCE);
        project.tick(0L);
        return project;
    }

    private static WorkKey keyFor(AgentId who) {
        return new WorkKey.ForMember(WorkKey.GATHER, who);
    }

    /** What a body that asks actually takes: the first slice, coalesced and then claimed. */
    private static WorkItem claims(Gather project, AgentId who, BoardBrainContext ctx) {
        WorkItem trip = project.realise(project.open().get(0), who, ctx);
        project.claimed(trip, who);
        return trip;
    }

    /** The same, through the board — which is the path {@link Project#owns} is load-bearing on. */
    private static WorkItem takes(PartyBoard board, Gather project, AgentId who,
                                  BoardBrainContext ctx) {
        WorkItem trip = project.realise(project.open().get(0), who, ctx);
        assertTrue(board.claim(trip, who, 0L), "the board leases what realise handed back");
        return trip;
    }

    /** Every storage slot full but one stack of logs with exactly {@code room} of headroom. */
    private static BoardBrainContext packWithRoomFor(int room) {
        BoardBrainContext ctx = new BoardBrainContext();
        for (int slot = 0; slot < Inventory.ARMOR_START; slot++) {
            ctx.inventory().set(slot, ItemStack.of("minecraft:cobblestone", 64, 64));
        }
        ctx.inventory().set(0, ItemStack.of("minecraft:spruce_log", 64 - room, 64));
        return ctx;
    }

    /** The slate's slice sizes, read off the offer in order. */
    private static List<Integer> sliceSizes(Gather project) {
        List<Integer> sizes = new ArrayList<>();
        for (WorkItem item : project.open()) {
            Matcher m = Pattern.compile("fetch (\\d+) ").matcher(item.describe());
            if (m.find()) {
                sizes.add(Integer.parseInt(m.group(1)));
            }
        }
        return sizes;
    }

    /** How big a trip is, read off the line a readout would show. */
    private static int tripSize(WorkItem item) {
        return Integer.parseInt(item.describe().split(" ")[1]);
    }

    /** The line a trip of this size shows, built from the spec so it cannot drift from the code. */
    private static String fetchLine(int size) {
        return "fetch " + size + " " + Stock.LOGS.name() + " to (10, 64, 10)";
    }

    /**
     * A member back from the yard: they remember the chest, and they remember what was in it when
     * they closed the lid. Both facts are the depositor's, never the board's.
     */
    private static BoardBrainContext depositor(Pos chest, int held) {
        BoardBrainContext ctx = new BoardBrainContext();
        saw(ctx, chest, held, ctx.now());
        return ctx;
    }

    /** A member standing at this tick, remembering nothing about the yard yet. */
    private static BoardBrainContext reporterAt(long tick) {
        BoardBrainContext ctx = new BoardBrainContext();
        ctx.advance(tick);
        return ctx;
    }

    /** Gives this member a memory of that chest, and of what was in it when they looked. */
    private static void saw(BoardBrainContext ctx, Pos chest, int held, long when) {
        ctx.remember(Store.POI, chest);
        ctx.knowledge().sawInside(chest, List.of(ItemStack.of("minecraft:oak_log", held, 64)),
                when, AgentKnowledge.maxPerKind(ctx.profile()));
    }

    // ── the arithmetic ───────────────────────────────────────────────────────────────────────

    @Test
    void theRemainderIsTheTargetLessWhatIsBankedAndWhatIsOut() {
        Gather project = posted(256);

        assertEquals(0, project.stored(), "nothing is believed until somebody looks");
        assertEquals(0, project.inFlight(), "and nothing is out until somebody claims");
        assertEquals(256, project.remainder());

        claims(project, KYLE, new BoardBrainContext());

        assertEquals(64, project.inFlight(), "one full carry, for the one body that asked");
        assertEquals(192, project.remainder());
    }

    // ── the slate ────────────────────────────────────────────────────────────────────────────

    @Test
    void theSlateIsCutIntoSlicesAndTheTailFoldsIn() {
        Gather project = posted(65);
        assertEquals(List.of(16, 16, 16, 17), sliceSizes(project),
                "65 is four slices, the last carrying the remainder — never a runt of one");
    }

    @Test
    void aSmallJobStaysOnePersons() {
        Gather project = posted(16);
        assertEquals(List.of(16), sliceSizes(project), "sixteen is one armful, not four errands");
    }

    @Test
    void aRemainderTooSmallToSliceIsStillOfferedWhole() {
        Gather project = posted(9);
        assertEquals(List.of(9), sliceSizes(project),
                "MIN_TRIP is the smallest slice worth cutting, not the smallest job worth doing");
    }

    @Test
    void theOfferKeepsItsIdentityAcrossBeats() {
        Gather project = posted(512);
        WorkItem trip = claims(project, KYLE, new BoardBrainContext());
        WorkItem slice = project.open().get(0);

        project.tick(40L);
        project.tick(80L);

        assertSame(trip, project.itemFor(keyFor(KYLE)).orElseThrow(),
                "the board leases by IDENTITY: a re-minted trip drops a live hold");
        assertSame(slice, project.open().get(0),
                "and a beat that moved nothing must not re-cut the slate under the maps keyed on "
                        + "it either");
    }

    // ── how a claim is sized ─────────────────────────────────────────────────────────────────

    @Test
    void aClaimCoalescesAdjacentSlices() {
        Gather project = posted(65);
        WorkItem trip = project.realise(project.open().get(0), KYLE, new BoardBrainContext());
        assertEquals(fetchLine(64), trip.describe(),
                "an empty pack takes MAX_TRIP in one trip, not one slice");
    }

    @Test
    void aClaimIsCappedByThePack() {
        Gather project = posted(512);
        assertEquals(fetchLine(24),
                project.realise(project.open().get(0), KYLE, packWithRoomFor(24)).describe());
    }

    @Test
    void realiseCommitsNothing() {
        Gather project = posted(512);
        for (int i = 0; i < 50; i++) {
            project.realise(project.open().get(0), KYLE, new BoardBrainContext());
        }
        assertEquals(0, project.inFlight(),
                "asking fifty times must reserve nothing — the arbiter asks every tick");
        assertEquals(512, project.remainder());
    }

    @Test
    void theClaimIsWhatCommits() {
        Gather project = posted(512);
        WorkItem trip = project.realise(project.open().get(0), KYLE, new BoardBrainContext());
        project.claimed(trip, KYLE);
        assertEquals(64, project.inFlight());
        assertEquals(448, project.remainder());
    }

    @Test
    void aMemberWhoNeverAsksIsNeverReservedAnything() {
        // The 2026-08-27 stall: a party whose first member is a player, who runs no arbiter and
        // therefore never asks. Nothing may be held against them.
        Gather project = posted(512);
        project.tick(0L);
        project.tick(100L);
        project.tick(1000L);
        assertEquals(0, project.inFlight(),
                "ticking must reserve nothing for anybody, ever");
    }

    @Test
    void nothingIsOfferedOnceTheOutstandingWorkIsClaimed() {
        Gather project = posted(64);
        WorkItem trip = claims(project, KYLE, new BoardBrainContext());

        project.tick(40L);

        assertEquals(0, project.remainder());
        assertEquals(List.of(trip), project.open(),
                "the slate is empty and the one trip out is the whole offer");
        assertFalse(project.offerableTo(trip, SAM, new BoardBrainContext()),
                "and somebody else's trip is not work — it is indistinguishable from no work");
    }

    // ── who owns what ────────────────────────────────────────────────────────────────────────

    /**
     * The chicken-and-egg {@code Project.owns} exists to break: {@code Board.claim} resolves an
     * item's project before the project has been told about it, so a trip {@code realise} has just
     * handed out is on offer to nobody. Unrecognised, the claim is not refused — it is never
     * routed, and every later completion, failure and expiry goes unrouted with it.
     */
    @Test
    void aRealisedTripIsOwnedBeforeItIsCommitted() {
        Gather project = posted(512);
        WorkItem trip = project.realise(project.open().get(0), KYLE, new BoardBrainContext());

        assertFalse(project.open().contains(trip), "nothing holds it yet");
        assertTrue(project.owns(trip), "and the board can still find its project");
    }

    @Test
    void oneGatherDoesNotAnswerForAnothersTrip() {
        Gather mine = posted(512);
        Gather theirs = posted(512);
        WorkItem trip = mine.realise(mine.open().get(0), KYLE, new BoardBrainContext());

        assertTrue(mine.owns(trip));
        assertFalse(theirs.owns(trip),
                "two gathers on one board mint the same inner types — answering by class alone "
                        + "would file every trip under whichever was posted first");
    }

    // ── outcomes ─────────────────────────────────────────────────────────────────────────────

    @Test
    void aLapsedTripGoesBackToTheSlate() {
        // What replaces the roster sweep: a claimant who walks off, dies or unloads stops
        // heartbeating, the board expires the hold, and the amount returns to the remainder.
        Gather project = posted(512);
        WorkItem trip = project.realise(project.open().get(0), KYLE, new BoardBrainContext());
        project.claimed(trip, KYLE);
        assertEquals(448, project.remainder());

        project.lapsed(trip);

        assertEquals(0, project.inFlight(), "a lapsed trip is held against nobody");
        assertEquals(512, project.remainder(), "and its amount is outstanding again");
    }

    @Test
    void aLapsedClaimDoesNotStartACooldown() {
        Gather project = posted(64);
        WorkItem trip = claims(project, KYLE, new BoardBrainContext());

        project.lapsed(trip);
        project.tick(40L);

        assertTrue(project.offerableTo(project.open().get(0), KYLE, new BoardBrainContext()),
                "the worker was pulled away, not proven wrong — that says nothing about whether "
                        + "the trip is doable");
    }

    @Test
    void aFailedMemberIsOfferedNothingUntilTheCooldownExpires() {
        Gather project = posted(64);
        WorkItem trip = claims(project, KYLE, new BoardBrainContext());

        project.failed(trip, new BoardBrainContext());

        assertEquals(0, project.inFlight());
        project.tick(40L);
        assertFalse(project.offerableTo(project.open().get(0), KYLE, new BoardBrainContext()),
                "the same body just proved this trip impossible — re-offering it immediately is "
                        + "the defect this pacing exists to close");

        project.tick(Gather.FAIL_COOLDOWN + 1);

        assertTrue(project.offerableTo(project.open().get(0), KYLE, new BoardBrainContext()),
                "past the cooldown the member is exactly as free as anybody else");
    }

    @Test
    void aDifferentMemberIsStillOfferedWorkImmediately() {
        Gather project = posted(512);
        WorkItem trip = claims(project, KYLE, new BoardBrainContext());

        project.failed(trip, new BoardBrainContext());
        project.tick(40L);

        WorkItem slice = project.open().get(0);
        assertFalse(project.offerableTo(slice, KYLE, new BoardBrainContext()),
                "cooling bars the whole project, not just the trip that already failed");
        assertTrue(project.offerableTo(slice, SAM, new BoardBrainContext()),
                "\"no jungle in reach\" is a fact about the failing body's surroundings — a "
                        + "settler who never touched that trip must not pay for it");
    }

    @Test
    void aBodyWithNoRoomIsOfferedNothing() {
        Gather project = posted(512);
        BoardBrainContext full = packWithRoomFor(0);

        assertFalse(project.offerableTo(project.open().get(0), KYLE, full),
                "declining on the asker's own pack is what the three-arg offerableTo is for — a "
                        + "body with nowhere to put a log must not claim a trip it cannot work");
    }

    /**
     * The hole this closes: {@code Board.bestFor} never asked who an item belonged to, so a cooling
     * member was handed a DIFFERENT member's trip, failed that one too, and benched off the whole
     * board (live, 2026-08-24, settler {@code Di}).
     */
    @Test
    void aTripIsOfferableOnlyToTheMemberWhoClaimedIt() {
        Gather project = posted(512);
        WorkItem trip = claims(project, KYLE, new BoardBrainContext());

        assertTrue(project.offerableTo(trip, KYLE, new BoardBrainContext()),
                "a body resuming after a suspension must find its own work again");
        assertFalse(project.offerableTo(trip, SAM, new BoardBrainContext()),
                "a claimed trip must never go to another body");
    }

    // ── the ledger ───────────────────────────────────────────────────────────────────────────

    @Test
    void aDepositTeachesTheProjectWhatTheChestHolds() {
        Gather project = posted(64);
        WorkItem trip = claims(project, KYLE, new BoardBrainContext());

        project.completed(trip, depositor(CHEST, 16));

        assertEquals(List.of(CHEST), project.yardChests(),
                "the readout names where the goods are, not where they were asked for");
        assertEquals(16, project.stored());
        assertEquals(48, project.remainder());
    }

    @Test
    void aChestNowhereNearTheHintIsNotThisProjectsYard() {
        Gather project = posted(64);
        WorkItem trip = claims(project, KYLE, new BoardBrainContext());

        project.completed(trip, depositor(new Pos(900, 64, 900), 64));

        assertTrue(project.yardChests().isEmpty(),
                "a worker's own chest across the map is not the party's yard");
        assertEquals(0, project.stored());
    }

    @Test
    void aChestTheReporterNoLongerRemembersDropsItsReading() {
        Gather project = posted(64);
        WorkItem first = claims(project, KYLE, new BoardBrainContext());
        project.completed(first, depositor(CHEST, 32));
        assertEquals(32, project.stored());

        project.tick(40L);
        WorkItem second = claims(project, SAM, new BoardBrainContext());
        // Somebody broke the chest and Store.wouldNotOpen disproved it — or this member's memory
        // cap simply evicted the place. The project cannot tell the two apart, and must not try.
        project.completed(second, reporterAt(40L));

        assertEquals(0, project.stored(),
                "the error has to be collect-too-much, never a project closing satisfied over an "
                        + "empty hole");
        assertFalse(project.finished());
        assertEquals(64, project.remainder());
        assertEquals(List.of(new Gather.Reading(CHEST, 0, 40L)), project.readings(),
                "and it is recorded as a reading of EMPTY taken now, not erased — an erased row "
                        + "has no tick for a later belief to lose to");
    }

    @Test
    void aReporterWhoNeverWentBackCannotRaiseADisprovedChest() {
        Gather project = posted(64);
        Pos other = new Pos(12, 64, 10);
        // Zoe banks 32 in the first chest and everybody's beliefs start there.
        project.completed(claims(project, KYLE, new BoardBrainContext()), depositor(CHEST, 32));
        assertEquals(32, project.stored());

        // A creeper takes that chest. Alice deposits into the second one; Store.wouldNotOpen has
        // disproved the first for her, so she cannot remember it at all.
        BoardBrainContext alice = reporterAt(100L);
        saw(alice, other, 32, 100L);
        project.completed(claims(project, SAM, new BoardBrainContext()), alice);
        assertEquals(32, project.stored(), "one chest gone, the other holding 32");

        // Bob looked inside the first chest last week and has never been back. His belief about it
        // is a week old; his deposit into the second one is now.
        BoardBrainContext bob = reporterAt(200L);
        saw(bob, CHEST, 32, 0L);
        saw(bob, other, 32, 200L);
        project.completed(claims(project, KYLE, new BoardBrainContext()), bob);

        assertEquals(32, project.stored(),
                "a chest disproved at tick 100 must not be raised again by a belief formed at "
                        + "tick 0 — that is a phantom, and the project would close over it");
        assertFalse(project.finished(),
                "closing satisfied over an empty hole is the one failure this ledger exists to "
                        + "prevent");
    }

    @Test
    void asecondChestAtTheYardIsCountedBesideTheFirst() {
        Gather project = posted(128);
        Pos second = new Pos(12, 64, 10);
        WorkItem trip = claims(project, KYLE, new BoardBrainContext());
        BoardBrainContext ctx = depositor(CHEST, 64);
        ctx.remember(Store.POI, second);
        ctx.knowledge().sawInside(second, List.of(ItemStack.of("minecraft:oak_log", 32, 64)),
                ctx.now(), AgentKnowledge.maxPerKind(ctx.profile()));

        project.completed(trip, ctx);

        assertEquals(List.of(CHEST, second), project.yardChests());
        assertEquals(96, project.stored(), "a full first chest does not end the count");
    }

    @Test
    void anOlderBeliefDoesNotOverwriteANewerReading() {
        Gather project = posted(256);
        WorkItem hers = claims(project, KYLE, new BoardBrainContext());
        WorkItem his = claims(project, SAM, new BoardBrainContext());
        BoardBrainContext late = depositor(CHEST, 64);
        late.advance(100L);
        late.knowledge().sawInside(CHEST, List.of(ItemStack.of("minecraft:oak_log", 64, 64)),
                late.now(), AgentKnowledge.maxPerKind(late.profile()));
        project.completed(hers, late);

        // He was at the yard an hour ago and has not been back since.
        project.completed(his, depositor(CHEST, 8));

        assertEquals(64, project.stored(),
                "the ledger is the chest as LAST read, and a stale memory is not a newer look");
    }

    // ── closing ──────────────────────────────────────────────────────────────────────────────

    @Test
    void theProjectFinishesWhenTheYardHoldsEnough() {
        Gather project = posted(32);
        WorkItem trip = claims(project, KYLE, new BoardBrainContext());
        assertFalse(project.finished());

        project.completed(trip, depositor(CHEST, 32));

        assertTrue(project.finished());
        assertTrue(project.open().isEmpty(), "and it stops offering the moment it is satisfied");
    }

    @Test
    void theClosingLineLandsInTheReportersJournalAndSaysItOnce() {
        Gather project = posted(32);
        WorkItem trip = claims(project, KYLE, new BoardBrainContext());
        BoardBrainContext ctx = depositor(CHEST, 32);

        project.completed(trip, ctx);

        // From completed(), not from finished() or a board hook: closeFinished drops a satisfied
        // project without a word and PartyBoard.tick holds no context at all, so a report is the
        // only moment a party project ever has a worker's journal to write in.
        List<Entry> lines = ctx.journal().recent(10);
        Entry closing = lines.get(lines.size() - 1);
        assertEquals("gather 32 logs at (10, 64, 10)", closing.event());
        assertFalse(closing.event().contains("done"),
                "the subject says what the project IS; how it went belongs in the detail, or the "
                        + "line reads 'done (...) done'");
        assertEquals("done — the yard holds 32", closing.detail());
    }

    @Test
    void aWorkerStillCarryingWhenItClosesIsNotTheProjectsProblem() {
        Gather project = posted(64);
        WorkItem hers = claims(project, KYLE, packWithRoomFor(32));
        WorkItem his = claims(project, SAM, packWithRoomFor(32));

        // The yard already held some, so her thirty-two satisfies the whole target.
        project.completed(hers, depositor(CHEST, 64));

        assertTrue(project.finished());
        assertSame(his, project.itemFor(keyFor(SAM)).orElseThrow(),
                "a satisfied target does not yank an errand out from under the member walking it");
        assertEquals(List.of(his), project.open());
    }

    // ── the errand ───────────────────────────────────────────────────────────────────────────

    @Test
    void aTripFetchesWithoutRaidingTheStoreItIsFilling() {
        Gather project = posted(64);
        WorkItem trip = claims(project, KYLE, new BoardBrainContext());

        GatheringErrand errand = assertInstanceOf(GatheringErrand.class, trip.root());
        List<Task> steps = errand.methods().get(0).decompose(new BoardBrainContext());

        assertEquals(3, steps.size(), "fetch it, get to the yard, put it down");
        ObtainItem fetch = assertInstanceOf(ObtainItem.class, steps.get(0));
        assertEquals(ObtainItem.Sources.NOT_STORES, fetch.sources(),
                "the remainder is measured against what the yard already holds, so an errand "
                        + "allowed to take from storage would be sent to fetch the very goods it "
                        + "is counting: wanting 64 with 32 banked, it would make a new 32 by "
                        + "emptying the yard");
        assertEquals(Stock.LOGS, fetch.spec());
        assertEquals(64, fetch.count());

        assertEquals(YARD, assertInstanceOf(EnsureStore.class, steps.get(1)).hint(),
                "the yard is a hint, and this is what grows a chest on it");

        PutItems deposit = assertInstanceOf(PutItems.class, steps.get(2));
        assertEquals(Stock.LOGS, deposit.spec());
        assertEquals(64, deposit.count());
        assertNull(deposit.at(),
                "the chest is resolved on arrival — nobody knows its anchor when the trip is taken");
    }

    /** A slice is scored and shown, never run: {@code realise} replaces it before it is leased. */
    @Test
    void aSliceIsNotSomethingAnybodyCanWalk() {
        Gather project = posted(512);
        WorkItem slice = project.open().get(0);

        assertThrows(IllegalStateException.class, slice::root,
                "a slice reaching an executor means a claim went round realise, and a silent "
                        + "no-op errand is the worst way to find that out");
    }

    // ── what a member must keep hold of ──────────────────────────────────────────────────────

    @Test
    void theCargoIsReservedSoNothingStowsItOnTheWay() {
        Gather project = posted(512);
        claims(project, KYLE, packWithRoomFor(24));
        claims(project, SAM, new BoardBrainContext());

        List<ItemCall> reserved = project.reserved();

        assertEquals(1, reserved.size());
        assertEquals(Stock.LOGS, reserved.get(0).spec());
        assertEquals(64, reserved.get(0).count(), "the largest live trip, since there is no asker");
    }

    @Test
    void aProjectWithNothingOutReservesNothing() {
        Gather project = posted(64);

        assertTrue(project.reserved().isEmpty(),
                "a slate is nobody's cargo — until a trip is claimed there is nothing to protect");
    }

    // ── the readout ──────────────────────────────────────────────────────────────────────────

    @Test
    void theReadoutSaysWhatIsWantedWhatIsBankedAndHowManyTripsAreOut() {
        Gather project = posted(128);
        claims(project, KYLE, new BoardBrainContext());
        claims(project, SAM, new BoardBrainContext());

        assertEquals("gather 128 logs at (10, 64, 10) — 0/128 in the yard, 2 trips out",
                project.describe());
    }

    // ── durable names ────────────────────────────────────────────────────────────────────────

    @Test
    void aTripIsNamedForTheMemberWhoClaimedIt() {
        Gather project = posted(512);
        WorkItem hers = claims(project, KYLE, new BoardBrainContext());

        WorkKey key = project.keyOf(hers).orElseThrow();

        assertEquals(keyFor(KYLE), key);
        assertSame(hers, project.itemFor(key).orElseThrow());
    }

    // ── continuity ───────────────────────────────────────────────────────────────────────────

    @Test
    void aSavedGatherComesBackWithItsYardAndItsLedger() {
        Gather project = posted(64);
        WorkItem trip = claims(project, KYLE, new BoardBrainContext());
        project.completed(trip, depositor(CHEST, 16));
        project.tick(40L);

        Gather back = Gather.restore(project.snapshot(), 40L).orElseThrow();

        assertEquals(List.of(CHEST), back.yardChests());
        assertEquals(16, back.stored());
        assertEquals(project.describe(), back.describe());
    }

    @Test
    void anUnknownSplitFallsBackRatherThanLosingTheProject() {
        Gather project = posted(64);
        Splits.clear();

        Gather back = Gather.restore(project.snapshot(), 0L).orElseThrow();

        assertSame(CarrySplit.INSTANCE, back.split(),
                "a policy is not identity — a removed strategy must not cost the party the job");
        Splits.register(CarrySplit.INSTANCE);
    }

    @Test
    void aGatherForSomethingThisBuildDoesNotKnowComesBackAsNothing() {
        Gather.State unknown = new Gather.State("dilithium", 64, YARD, 0.5, PARTY, "carry",
                List.of(), List.of(), List.of(), List.of());

        // Never silently an empty project: the store's job is to refuse the world, and it can only
        // do that if this says so rather than handing back something plausible.
        assertTrue(Gather.restore(unknown, 0L).isEmpty());
    }

    @Test
    void reloadingHandsEachMemberTheirOwnTripBack() {
        AgentId alice = AgentId.random();
        AgentId bob = AgentId.random();
        AgentId carol = AgentId.random();
        AgentId dan = AgentId.random();
        PartyBoard board = new PartyBoard(PARTY);
        Gather project = new Gather(Stock.LOGS, 64, YARD, 0.5, PARTY, CarrySplit.INSTANCE);
        board.post(project);
        board.tick(0L);

        // Alice and Bob have been and come back with sixteen each; Carol and Dan are mid-walk.
        // What matters is that the remainder no longer covers four more trips, so claim order and
        // hold order cannot coincide.
        board.completed(takes(board, project, alice, packWithRoomFor(16)), alice,
                depositor(CHEST, 16));
        board.completed(takes(board, project, bob, packWithRoomFor(16)), bob,
                depositor(CHEST, 32));
        assertEquals(32, project.stored());
        takes(board, project, carol, packWithRoomFor(16));
        takes(board, project, dan, packWithRoomFor(16));

        List<PartyBoard.Row> saved = board.snapshot(0L);
        PartyBoard reloaded = new PartyBoard(PARTY);
        assertEquals(0, reloaded.restore(saved, 0L));

        Gather back = (Gather) reloaded.projects().get(0);
        assertTrue(reloaded.holds(back.itemFor(keyFor(carol)).orElseThrow(), carol, 0L),
                "a member's id is exactly what survives a restart — Carol gets HER trip back, "
                        + "not whoever happens to ask first");
        assertTrue(reloaded.holds(back.itemFor(keyFor(dan)).orElseThrow(), dan, 0L));
        assertTrue(back.itemFor(keyFor(alice)).isEmpty(),
                "and nobody is sent for goods somebody else is already carrying");
        assertTrue(back.itemFor(keyFor(bob)).isEmpty());
        assertEquals(2, back.open().size());
        assertEquals(32, back.inFlight(), "exactly what was out when the world stopped");
        assertEquals(0, back.remainder());
    }

    /**
     * The seam a reload hangs on. {@code PartyBoard.restore} hands a lease straight back without
     * the bidding {@code claim} does, so it has to tell the project itself — and it must tell it
     * WHO, because a project that files a claim under its claimant hears nothing from the one-arg
     * form. Untold, the trip is not in {@code claimed}, so the moment the yard is satisfied
     * {@code withdrawAll} drops it out from under the body still walking: their report then lands
     * on an item {@code keyOf} cannot name, takes no yard reading, and their cargo stops being
     * reserved against {@code Unburden} on the way.
     */
    @Test
    void aReclaimedTripSurvivesTheFinishThatDropsTheSlate() {
        PartyBoard board = new PartyBoard(PARTY);
        Gather project = new Gather(Stock.LOGS, 64, YARD, 0.5, PARTY, CarrySplit.INSTANCE);
        board.post(project);
        board.tick(0L);
        takes(board, project, KYLE, packWithRoomFor(32));
        takes(board, project, SAM, packWithRoomFor(32));

        PartyBoard reloaded = new PartyBoard(PARTY);
        assertEquals(0, reloaded.restore(board.snapshot(0L), 0L));
        Gather back = (Gather) reloaded.projects().get(0);

        // Kyle gets back and the yard is satisfied. Sam is still mid-walk with his own thirty-two.
        reloaded.completed(back.itemFor(keyFor(KYLE)).orElseThrow(), KYLE, depositor(CHEST, 64));

        assertTrue(back.finished());
        assertTrue(back.itemFor(keyFor(SAM)).isPresent(),
                "a reclaimed hold is a hold — closing the project must not withdraw the trip its "
                        + "holder is still walking");
        assertEquals(32, back.inFlight(), "and it is still what he is carrying for");
    }

    @Test
    void aTripComesBackTheSizeItWasHandedOutAt() {
        Gather project = posted(512);
        claims(project, KYLE, new BoardBrainContext());
        claims(project, SAM, new BoardBrainContext());

        Gather back = Gather.restore(project.snapshot(), 0L).orElseThrow();

        assertEquals(64, tripSize(back.itemFor(keyFor(KYLE)).orElseThrow()));
        assertEquals(64, tripSize(back.itemFor(keyFor(SAM)).orElseThrow()));
        assertEquals(128, back.inFlight());
    }

    @Test
    void theCooldownSurvivesSnapshotAndRestore() {
        Gather project = posted(64);
        WorkItem hers = claims(project, KYLE, new BoardBrainContext());
        BoardBrainContext ctx = new BoardBrainContext();
        ctx.advance(100L);
        project.failed(hers, ctx);

        Gather back = Gather.restore(project.snapshot(), 100L).orElseThrow();

        assertFalse(back.offerableTo(back.open().get(0), KYLE, new BoardBrainContext()),
                "the house rule is that a reboot is invisible — an agent must not be able to tell "
                        + "one happened, and a cooldown that failed to round-trip would let this "
                        + "member straight back in");
        assertTrue(back.offerableTo(back.open().get(0), SAM, new BoardBrainContext()),
                "the unaffected member reloads unaffected");

        Gather further = Gather.restore(back.snapshot(), 100L + Gather.FAIL_COOLDOWN).orElseThrow();

        assertTrue(further.offerableTo(further.open().get(0), KYLE, new BoardBrainContext()),
                "and it actually expires — a saved cooldown is not a permanent ban");
    }
}
