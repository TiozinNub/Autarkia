package dev.luizloyola.autarkia.core.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.board.WorkItem;
import dev.luizloyola.anima.core.brain.board.WorkSource;
import dev.luizloyola.anima.core.brain.instinct.Instinct;
import dev.luizloyola.anima.core.brain.task.Task;
import dev.luizloyola.anima.core.social.PartyId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Reaching two boards as if they were one — and, more importantly, not merging them: an outcome
 * has to find its way back to the board that offered the errand, or a party project would be told
 * its work is done by an agent who was doing something else entirely.
 */
class ComposedBoardsTest {

    private final BoardBrainContext ctx = new BoardBrainContext();
    private final AgentId me = AgentId.random();
    private final PersonalBoard personal = new PersonalBoard();
    private final PartyBoard party = new PartyBoard(PartyId.random());
    private final ComposedBoards work =
            new ComposedBoards(personal.viewFor(() -> me), () -> party.viewFor(() -> me));

    @Test
    void offersTheBetterBidWhicheverBoardItIsOn() {
        Offering mine = new Offering("mine", 0.3);
        Offering theirs = new Offering("theirs", 0.8);
        personal.post(mine);
        party.post(theirs);

        assertEquals("theirs", work.bestAvailable(ctx).orElseThrow().describe(),
                "the party's louder errand wins on the shared scale");
        theirs.bid = 0.1;
        assertEquals("mine", work.bestAvailable(ctx).orElseThrow().describe());
    }

    /** A tie goes to the agent's own want — the same instinct that made a personal board exist. */
    @Test
    void anExactTieGoesToTheirOwnBoard() {
        personal.post(new Offering("mine", 0.5));
        party.post(new Offering("theirs", 0.5));
        assertEquals("mine", work.bestAvailable(ctx).orElseThrow().describe());
    }

    @Test
    void anOutcomeGoesBackToTheBoardThatOfferedIt() {
        Offering mine = new Offering("mine", 0.3);
        Offering theirs = new Offering("theirs", 0.8);
        personal.post(mine);
        party.post(theirs);

        WorkItem item = work.bestAvailable(ctx).orElseThrow();
        work.claimed(item, ctx);
        work.completed(item, ctx);

        assertEquals(List.of("claimed", "completed"), theirs.events, "the party's project was told");
        assertTrue(mine.events.isEmpty(), "the personal project heard nothing about it");
    }

    /**
     * The claim outlives the offer: a project can withdraw its last item while the worker is
     * still walking, and the outcome must still land somewhere sensible.
     */
    @Test
    void anOutcomeStillLandsAfterTheOfferIsWithdrawn() {
        Offering theirs = new Offering("theirs", 0.8);
        party.post(theirs);
        WorkItem item = work.bestAvailable(ctx).orElseThrow();
        work.claimed(item, ctx);
        theirs.items.clear(); // withdrawn under the worker

        work.failed(item, ctx);
        assertEquals(List.of("claimed"), theirs.events,
                "nothing to route to, and nothing thrown either");
    }

    /**
     * Only the personal side has a cadence here. The party board thinks once per board in its
     * host, not once per member — ticking it from every member is the bug this asserts against.
     */
    @Test
    void tickReachesTheirOwnBoardOnly() {
        CountingSource personalSide = new CountingSource();
        CountingSource partySide = new CountingSource();
        new ComposedBoards(personalSide, () -> partySide).tick(ctx);
        assertEquals(1, personalSide.ticks);
        assertEquals(0, partySide.ticks);
    }

    /**
     * ...and the beat has to survive the whole way down, through the view, to the project.
     *
     * <p>Every layer once forwarded the tick and the projects still never thought: the member's
     * <em>view</em>, the thing the composite holds, had taken the interface's do-nothing default. A
     * stand-in source proves the composite routes; only a real board proves it lands.
     */
    @Test
    void theBeatReachesTheProjectsThroughTheView() {
        Ticking project = new Ticking();
        personal.post(project);
        work.tick(ctx);
        work.tick(ctx);
        assertEquals(2, project.ticks, "a project on a personal board thinks every brain tick");
    }

    /** Dormant, but wired: neither side is allowed to be the only one that hears it. */
    @Test
    void aFailedDriveIsReportedToBothBoards() {
        CountingSource personalSide = new CountingSource();
        CountingSource partySide = new CountingSource();
        new ComposedBoards(personalSide, () -> partySide).driveFailed(null, "no food", ctx);
        assertEquals(1, personalSide.drives);
        assertEquals(1, partySide.drives);
    }

    @Test
    void theReadoutShowsBothScopes() {
        personal.post(new Offering("mine", 0.3));
        List<String> lines = work.describeLines(ctx);
        assertEquals(List.of(
                        "personal: 1 project",
                        "  #1 mine — 1 item (open)",
                        "party: nothing posted"),
                lines,
                "each scope announces itself, even the empty one — quiet and absent read alike "
                        + "from a summary, and they are not the same thing");
    }

    /** A membership change swaps the board under the agent without rebuilding the composite. */
    @Test
    void theirPartyBoardFollowsTheirMembership() {
        PartyBoard first = new PartyBoard(PartyId.random());
        PartyBoard second = new PartyBoard(PartyId.random());
        first.post(new Offering("first", 0.8));
        second.post(new Offering("second", 0.8));
        PartyBoard[] current = {first};
        ComposedBoards composed =
                new ComposedBoards(personal.viewFor(() -> me), () -> current[0].viewFor(() -> me));

        assertEquals("first", composed.bestAvailable(ctx).orElseThrow().describe());
        current[0] = second;
        assertEquals("second", composed.bestAvailable(ctx).orElseThrow().describe());
    }

    // ---- doubles ------------------------------------------------------------------------

    /** A project offering one item at an adjustable bid, remembering what it was told. */
    private static final class Offering implements Project {
        private final String name;
        final List<WorkItem> items = new ArrayList<>();
        final List<String> events = new ArrayList<>();
        double bid;

        Offering(String name, double bid) {
            this.name = name;
            this.bid = bid;
            this.items.add(new WorkItem() {
                @Override
                public double priority() {
                    return Offering.this.bid;
                }

                @Override
                public Task root() {
                    throw new UnsupportedOperationException("no test here runs the work");
                }

                @Override
                public String describe() {
                    return name;
                }
            });
        }

        @Override
        public double priority() {
            return bid;
        }

        @Override
        public List<WorkItem> open() {
            return List.copyOf(items);
        }

        @Override
        public boolean finished() {
            return false;
        }

        @Override
        public void claimed(WorkItem item) {
            events.add("claimed");
        }

        @Override
        public void completed(WorkItem item, BrainContext ctx) {
            events.add("completed");
        }

        @Override
        public void failed(WorkItem item, BrainContext ctx) {
            events.add("failed");
        }

        @Override
        public String describe() {
            return name;
        }
    }

    /** A personal project that offers nothing and only counts the beats it is given. */
    private static final class Ticking implements PersonalProject {
        int ticks;

        @Override
        public void tick(BrainContext ctx) {
            ticks++;
        }

        @Override
        public double priority() {
            return 0.5;
        }

        @Override
        public List<WorkItem> open() {
            return List.of();
        }

        @Override
        public boolean finished() {
            return false;
        }

        @Override
        public void completed(WorkItem item, BrainContext ctx) {
        }

        @Override
        public void failed(WorkItem item, BrainContext ctx) {
        }

        @Override
        public String describe() {
            return "ticking";
        }
    }

    /** A work source that offers nothing and only counts what it is asked to do. */
    private static final class CountingSource implements WorkSource {
        int ticks;
        int drives;

        @Override
        public Optional<WorkItem> bestAvailable(BrainContext ctx) {
            return Optional.empty();
        }

        @Override
        public void claimed(WorkItem item, BrainContext ctx) {
        }

        @Override
        public void completed(WorkItem item, BrainContext ctx) {
        }

        @Override
        public void failed(WorkItem item, BrainContext ctx) {
        }

        @Override
        public void tick(BrainContext ctx) {
            ticks++;
        }

        @Override
        public void driveFailed(Instinct instinct, String detail, BrainContext ctx) {
            drives++;
        }
    }
}
