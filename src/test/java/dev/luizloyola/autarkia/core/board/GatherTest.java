package dev.luizloyola.autarkia.core.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.board.WorkItem;
import dev.luizloyola.anima.core.brain.knowledge.AgentKnowledge;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.inv.ItemCall;
import dev.luizloyola.anima.core.inv.ItemStack;
import dev.luizloyola.anima.core.log.Entry;
import dev.luizloyola.anima.core.social.PartyId;
import dev.luizloyola.anima.core.store.Store;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The ledger, the split and the two edges — everything that decides whether a party's quota gets
 * filled, proven without a world, a body or a block of perception.
 *
 * <p>Blind, like {@code ClearAreaTest}: a deposit here is a member handing back what they
 * "remember" of a chest they just opened. That is exactly what a real one is — {@code PutItems}
 * writes the sighting as it moves the stacks, and the project reads it off their memory.
 */
class GatherTest {

    private static final Pos YARD = new Pos(10, 64, 10);

    /** A block off the hint, because a yard is a hint and the chest lands where ground allows. */
    private static final Pos CHEST = new Pos(11, 64, 10);

    private static final PartyId PARTY = PartyId.of(UUID.randomUUID());

    /** Who is in the party right now — the roster the installed seam answers from. */
    private List<AgentId> roster = List.of();

    @BeforeEach
    void wireTheRoster() {
        PartyMembers.asks(party -> roster);
        Splits.register(EvenSplit.INSTANCE);
        PartyProjects.register(Gather.TYPE);
    }

    @AfterEach
    void unwireTheRoster() {
        PartyMembers.reset();
    }

    private void party(int size) {
        List<AgentId> members = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            members.add(AgentId.random());
        }
        this.roster = List.copyOf(members);
    }

    private Gather posted(int target) {
        Gather project = new Gather(Stock.LOGS, target, YARD, 0.5, PARTY, EvenSplit.INSTANCE);
        project.tick(0L);
        return project;
    }

    private static WorkKey keyFor(AgentId who) {
        return new WorkKey.ForMember(WorkKey.GATHER, who);
    }

    private WorkItem tripOf(Gather project, int member) {
        return project.itemFor(keyFor(roster.get(member))).orElseThrow();
    }

    /** How big a trip is, read off the line a readout would show. */
    private static int tripSize(WorkItem item) {
        return Integer.parseInt(item.describe().split(" ")[1]);
    }

    /**
     * A member back from the yard: they remember the chest, and they remember what was in it when
     * they closed the lid. Both facts are the depositor's, never the board's.
     */
    private static BoardBrainContext depositor(Pos chest, int held) {
        BoardBrainContext ctx = new BoardBrainContext();
        ctx.remember(Store.POI, chest);
        ctx.knowledge().sawInside(chest, List.of(ItemStack.of("minecraft:oak_log", held, 64)),
                ctx.now(), AgentKnowledge.maxPerKind(ctx.profile()));
        return ctx;
    }

    // ── the arithmetic ───────────────────────────────────────────────────────────────────────

    @Test
    void theRemainderIsTheTargetLessWhatIsBankedAndWhatIsOut() {
        party(2);
        Gather project = posted(256);

        assertEquals(0, project.stored(), "nothing is believed until somebody looks");
        assertEquals(128, project.inFlight(), "two members, one full carry each");
        assertEquals(128, project.remainder(), "and the rest waits for them to come back");
    }

    @Test
    void aPartyOfNobodyPostsNothing() {
        PartyMembers.reset();

        Gather project = posted(64);

        assertTrue(project.open().isEmpty(), "an un-wired roster is an idle project, not a crash");
        assertEquals(64, project.remainder());
    }

    // ── how the work splits ──────────────────────────────────────────────────────────────────

    @Test
    void fourMembersEachGetASixteenTripForOneStack() {
        party(4);

        Gather project = posted(64);

        assertEquals(4, project.open().size());
        for (WorkItem trip : project.open()) {
            assertEquals(16, tripSize(trip), "four settlers, four trips — not four full stacks");
        }
        assertEquals(64, project.inFlight());
        assertEquals(0, project.remainder());
    }

    @Test
    void oneStackAcrossEightStillOnlySendsFour() {
        party(8);

        Gather project = posted(64);

        assertEquals(4, project.open().size(),
                "the share shrinks as the pass hands trips out — the other four are offered "
                        + "nothing and go do something else");
        assertEquals(64, project.inFlight());
    }

    @Test
    void aBigJobIsCappedAtWhatIsWorthCarrying() {
        party(4);

        Gather project = posted(512);

        assertEquals(4, project.open().size());
        for (WorkItem trip : project.open()) {
            assertEquals(64, tripSize(trip));
        }
        assertEquals(256, project.remainder(), "the rest is re-minted as these deposit");
    }

    @Test
    void aSmallJobStaysOnePersons() {
        party(4);

        Gather project = posted(16);

        assertEquals(1, project.open().size(), "sixteen is one armful, not four errands");
        assertEquals(16, tripSize(project.open().get(0)));
    }

    @Test
    void nobodyIsOfferedAnythingOnceTheOutstandingWorkIsClaimed() {
        party(5);
        Gather project = posted(64);
        AgentId spare = roster.get(4);
        assertTrue(project.itemFor(keyFor(spare)).isEmpty(), "one stack is four trips, not five");
        for (WorkItem trip : project.open()) {
            project.claimed(trip);
        }

        project.tick(40L);

        assertEquals(4, project.open().size());
        assertTrue(project.itemFor(keyFor(spare)).isEmpty(),
                "the whole stack is spoken for, so this project has nothing for the fifth — and "
                        + "that is indistinguishable from it not existing");
        assertEquals(0, project.remainder());
    }

    @Test
    void aTripKeepsItsIdentityAcrossBeats() {
        party(4);
        Gather project = posted(64);
        WorkItem first = tripOf(project, 0);

        project.tick(40L);
        project.tick(80L);

        assertSame(first, tripOf(project, 0),
                "the board leases by IDENTITY: a re-minted item drops a live hold");
    }

    @Test
    void aMemberWhoLeavesThePartyLosesTheirTrip() {
        party(4);
        Gather project = posted(64);
        AgentId gone = roster.get(3);
        roster = roster.subList(0, 3);

        project.tick(40L);

        assertTrue(project.itemFor(keyFor(gone)).isEmpty());
        assertEquals(3, project.open().size());
        assertEquals(16, project.remainder(), "and their share is outstanding again");
    }

    // ── outcomes ─────────────────────────────────────────────────────────────────────────────

    @Test
    void aLapsedTripReopensAndIsOfferedAgain() {
        party(4);
        Gather project = posted(64);
        WorkItem hers = tripOf(project, 0);
        project.claimed(hers);

        project.lapsed(hers);

        assertEquals(48, project.inFlight());
        assertEquals(16, project.remainder(), "work is never stranded on one settler");

        project.tick(40L);

        assertEquals(4, project.open().size());
        WorkItem fresh = tripOf(project, 0);
        assertNotSame(hers, fresh);
        assertEquals(16, tripSize(fresh));
    }

    @Test
    void aFailedFetchIsSimplyOfferedAgain() {
        party(1);
        Gather project = posted(64);
        WorkItem trip = tripOf(project, 0);
        project.claimed(trip);

        project.failed(trip, new BoardBrainContext());

        assertEquals(0, project.inFlight());
        project.tick(40L);
        assertEquals(1, project.open().size(),
                "no cooldown in v1 — a quantity says nothing about where it failed");
    }

    @Test
    void aDepositTeachesTheProjectWhatTheChestHolds() {
        party(1);
        Gather project = posted(64);
        WorkItem trip = tripOf(project, 0);
        project.claimed(trip);

        project.completed(trip, depositor(CHEST, 16));

        assertEquals(List.of(CHEST), project.yardChests(),
                "the readout names where the goods are, not where they were asked for");
        assertEquals(16, project.stored());
        assertEquals(48, project.remainder());
    }

    @Test
    void aChestNowhereNearTheHintIsNotThisProjectsYard() {
        party(1);
        Gather project = posted(64);
        WorkItem trip = tripOf(project, 0);
        project.claimed(trip);

        project.completed(trip, depositor(new Pos(900, 64, 900), 64));

        assertTrue(project.yardChests().isEmpty(),
                "a worker's own chest across the map is not the party's yard");
        assertEquals(0, project.stored());
    }

    @Test
    void aChestTheReporterNoLongerRemembersDropsItsReading() {
        party(1);
        Gather project = posted(64);
        WorkItem first = tripOf(project, 0);
        project.claimed(first);
        project.completed(first, depositor(CHEST, 32));
        assertEquals(32, project.stored());

        project.tick(40L);
        WorkItem second = tripOf(project, 0);
        project.claimed(second);
        // Somebody broke the chest and Store.wouldNotOpen disproved it — or this member's memory
        // cap simply evicted the place. The project cannot tell the two apart, and must not try.
        project.completed(second, new BoardBrainContext());

        assertEquals(0, project.stored(),
                "the error has to be collect-too-much, never a project closing satisfied over an "
                        + "empty hole");
        assertFalse(project.finished());
        assertEquals(64, project.remainder());
    }

    @Test
    void asecondChestAtTheYardIsCountedBesideTheFirst() {
        party(1);
        Gather project = posted(128);
        Pos second = new Pos(12, 64, 10);
        WorkItem trip = tripOf(project, 0);
        project.claimed(trip);
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
        party(2);
        Gather project = posted(256);
        WorkItem hers = tripOf(project, 0);
        WorkItem his = tripOf(project, 1);
        project.claimed(hers);
        project.claimed(his);
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
        party(1);
        Gather project = posted(32);
        WorkItem trip = tripOf(project, 0);
        project.claimed(trip);
        assertFalse(project.finished());

        project.completed(trip, depositor(CHEST, 32));

        assertTrue(project.finished());
        assertTrue(project.open().isEmpty(), "and it stops offering the moment it is satisfied");
    }

    @Test
    void theClosingLineLandsInTheReportersJournalAndSaysItOnce() {
        party(1);
        Gather project = posted(32);
        WorkItem trip = tripOf(project, 0);
        project.claimed(trip);
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
        party(2);
        Gather project = posted(64);
        WorkItem hers = tripOf(project, 0);
        WorkItem his = tripOf(project, 1);
        project.claimed(hers);
        project.claimed(his);

        // The yard already held some, so her thirty-two satisfies the whole target.
        project.completed(hers, depositor(CHEST, 64));

        assertTrue(project.finished());
        assertSame(his, project.itemFor(keyFor(roster.get(1))).orElseThrow(),
                "a satisfied target does not yank an errand out from under the member walking it");
        assertEquals(List.of(his), project.open());
    }

    // ── what a member must keep hold of ──────────────────────────────────────────────────────

    @Test
    void theCargoIsReservedSoNothingStowsItOnTheWay() {
        party(4);
        Gather project = posted(64);

        List<ItemCall> reserved = project.reserved();

        assertEquals(1, reserved.size());
        assertEquals(Stock.LOGS, reserved.get(0).spec());
        assertEquals(16, reserved.get(0).count(), "the largest live trip, since there is no asker");
    }

    @Test
    void aProjectWithNothingOutReservesNothing() {
        party(0);
        Gather project = posted(64);

        assertTrue(project.reserved().isEmpty(), "nothing is out, so nothing is cargo");
    }

    // ── the readout ──────────────────────────────────────────────────────────────────────────

    @Test
    void theReadoutSaysWhatIsWantedWhatIsBankedAndHowManyTripsAreOut() {
        party(2);
        Gather project = posted(128);

        assertEquals("gather 128 logs at (10, 64, 10) — 0/128 in the yard, 2 trips out",
                project.describe());
    }

    // ── durable names ────────────────────────────────────────────────────────────────────────

    @Test
    void aTripIsNamedForTheMemberItWasMintedFor() {
        party(2);
        Gather project = posted(64);
        WorkItem hers = tripOf(project, 0);

        WorkKey key = project.keyOf(hers).orElseThrow();

        assertEquals(new WorkKey.ForMember(WorkKey.GATHER, roster.get(0)), key);
        assertSame(hers, project.itemFor(key).orElseThrow());
    }

    // ── continuity ───────────────────────────────────────────────────────────────────────────

    @Test
    void aSavedGatherComesBackWithItsYardAndItsLedger() {
        party(1);
        Gather project = posted(64);
        WorkItem trip = tripOf(project, 0);
        project.claimed(trip);
        project.completed(trip, depositor(CHEST, 16));
        project.tick(40L);

        Gather back = Gather.restore(project.snapshot(), 40L).orElseThrow();

        assertEquals(List.of(CHEST), back.yardChests());
        assertEquals(16, back.stored());
        assertEquals(project.describe(), back.describe());
    }

    @Test
    void anUnknownSplitFallsBackRatherThanLosingTheProject() {
        party(1);
        Gather project = posted(64);
        Splits.clear();

        Gather back = Gather.restore(project.snapshot(), 0L).orElseThrow();

        assertSame(EvenSplit.INSTANCE, back.split(),
                "a policy is not identity — a removed strategy must not cost the party the job");
        Splits.register(EvenSplit.INSTANCE);
    }

    @Test
    void aGatherForSomethingThisBuildDoesNotKnowComesBackAsNothing() {
        Gather.State unknown = new Gather.State("dilithium", 64, YARD, 0.5, PARTY, "even",
                List.of(), List.of());

        // Never silently an empty project: the store's job is to refuse the world, and it can only
        // do that if this says so rather than handing back something plausible.
        assertTrue(Gather.restore(unknown, 0L).isEmpty());
    }

    @Test
    void reloadingHandsEachMemberTheirOwnTripBack() {
        party(4);
        PartyBoard board = new PartyBoard(PARTY);
        Gather project = new Gather(Stock.LOGS, 64, YARD, 0.5, PARTY, EvenSplit.INSTANCE);
        board.post(project);
        board.tick(0L);
        AgentId alice = roster.get(0);
        AgentId bob = roster.get(1);
        assertTrue(board.claim(project.itemFor(keyFor(alice)).orElseThrow(), alice, 0L));
        assertTrue(board.claim(project.itemFor(keyFor(bob)).orElseThrow(), bob, 0L));

        List<PartyBoard.Row> saved = board.snapshot(0L);
        PartyBoard reloaded = new PartyBoard(PARTY);
        assertEquals(0, reloaded.restore(saved, 0L));

        Gather back = (Gather) reloaded.projects().get(0);
        assertTrue(reloaded.holds(back.itemFor(keyFor(alice)).orElseThrow(), alice, 0L),
                "a member's id is exactly what survives a restart — Alice gets HER trip, not "
                        + "whichever one scores best");
        assertTrue(reloaded.holds(back.itemFor(keyFor(bob)).orElseThrow(), bob, 0L));
        assertEquals(4, back.open().size());
    }
}
