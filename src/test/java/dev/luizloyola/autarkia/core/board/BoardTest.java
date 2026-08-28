package dev.luizloyola.autarkia.core.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.board.WorkItem;
import dev.luizloyola.anima.core.brain.board.WorkSource;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.Task;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The board itself: what it offers, to whom, and what it does with the answer. */
class BoardTest {

    private final BoardBrainContext ctx = new BoardBrainContext();
    private final Board board = new Board();
    private final AgentId alice = AgentId.random();
    private final AgentId bob = AgentId.random();

    @Test
    void offersTheBestScoringItemAndHidesWhatIsClaimed() {
        FakeProject project = new FakeProject("errands");
        WorkItem cheap = project.add(new FakeItem("near", 0.5, 0.1));
        WorkItem dear = project.add(new FakeItem("far", 0.5, 0.4));
        board.post(project);

        assertSame(cheap, board.bestFor(alice, ctx, ctx.now()).orElseThrow(),
                "same bid, lower cost -> the one that costs this asker less");
        board.claim(cheap, alice, ctx.now());
        assertSame(dear, board.bestFor(bob, ctx, ctx.now()).orElseThrow(),
                "the claimed one is gone from the pool; the next best is offered");
        board.claim(dear, bob, ctx.now());
        assertTrue(board.bestFor(alice, ctx, ctx.now()).isEmpty(), "everything is spoken for");
    }

    /**
     * Why scoring is per-asker rather than global: two agents asking at the same moment get
     * different answers, and specialization-by-proximity falls out with no auction.
     */
    @Test
    void twoAskersAreOfferedDifferentItems() {
        FakeProject project = new FakeProject("errands");
        WorkItem east = project.add(new PlacedItem("east", 0.5, new Pos(20, 0, 0)));
        WorkItem west = project.add(new PlacedItem("west", 0.5, new Pos(-20, 0, 0)));
        board.post(project);

        ctx.standAt(new Pos(18, 0, 0));
        assertSame(east, board.bestFor(alice, ctx, ctx.now()).orElseThrow());
        ctx.standAt(new Pos(-18, 0, 0));
        assertSame(west, board.bestFor(bob, ctx, ctx.now()).orElseThrow());
    }

    /**
     * The hole this fix closes: the board itself never knew who an item was minted for, so a
     * project's decline has to sit beside the lease and kit checks, not depend on either.
     */
    @Test
    void theBoardHonoursAProjectsDeclineBesideTheLeaseAndKitFilters() {
        FakeProject project = new FakeProject("errands");
        WorkItem item = project.add(new FakeItem("hers", 0.5, 0.0));
        project.allow = (offered, who) -> who.equals(alice);
        board.post(project);

        assertSame(item, board.bestFor(alice, ctx, ctx.now()).orElseThrow(),
                "the project says this one is alice's to take");
        assertTrue(board.bestFor(bob, ctx, ctx.now()).isEmpty(),
                "and declines it for anybody else, exactly as a live lease would");
    }

    /**
     * A project declining its own item is not the same fact as a body lacking the kit — the
     * ordering comment in {@code Board.bestFor} exists to keep the two off the same journal line.
     */
    @Test
    void aProjectsDeclineIsNeverJournalledAsAPassedOverItem() {
        FakeProject project = new FakeProject("errands");
        project.add(new FakeItem("hers", 0.5, 0.0));
        project.allow = (offered, who) -> false;
        board.post(project);

        board.bestFor(bob, ctx, ctx.now());

        assertTrue(ctx.journal().recent(10).isEmpty(),
                "a project's own refusal is not a passed-over kit — nothing to log");
    }

    @Test
    void anAgentWithNoIdentityYetIsOfferedNothing() {
        FakeProject project = new FakeProject("errands");
        project.add(new FakeItem("anything", 0.5, 0.0));
        board.post(project);
        assertTrue(board.bestFor(null, ctx, ctx.now()).isEmpty(),
                "a body that does not know who it is cannot owe anybody an errand");
    }

    @Test
    void aClaimTellsTheProjectItsItemIsSpokenFor() {
        FakeProject project = new FakeProject("errands");
        WorkItem item = project.add(new FakeItem("one", 0.5, 0.0));
        board.post(project);
        board.claim(item, alice, ctx.now());
        assertEquals(List.of("claimed:one"), project.events);
    }

    /**
     * The hole this closes: a body with no pack room asks, and `bestFor` has no way to say no
     * without a project able to read live facts about the asker.
     */
    @Test
    void aProjectMayDeclineOnWhatTheAskerIsCarrying() {
        WorkItem item = new StubItem("slice", 0.5);
        RecordingProject project = new RecordingProject() {
            @Override
            public List<WorkItem> open() {
                return List.of(item);
            }

            @Override
            public boolean offerableTo(WorkItem offer, AgentId asker, BrainContext ctx) {
                return !ctx.percepts().inventory().isEmpty();
            }
        };
        Board board = new Board();
        board.post(project);

        assertTrue(board.bestFor(AgentId.random(), new BoardBrainContext(), 0L).isEmpty(),
                "a project declining on live facts should offer nothing");
    }

    @Test
    void aProjectIsToldWhoClaimed() {
        WorkItem item = new StubItem("slice", 0.5);
        RecordingProject project = new RecordingProject() {
            @Override
            public List<WorkItem> open() {
                return List.of(item);
            }

            @Override
            public void claimed(WorkItem claimedItem, AgentId who) {
                this.lastClaimed = claimedItem;
                this.lastClaimant = who;
            }
        };
        Board board = new Board();
        board.post(project);
        AgentId kyle = AgentId.random();

        board.claim(item, kyle, 0L);

        assertEquals(kyle, project.lastClaimant, "the project must learn who took it");
    }

    /**
     * A project that recognises an item it never put on offer — exactly what Gather's
     * realised-but-uncommitted trip is. Without owns(), the board cannot find its owner.
     */
    @Test
    void aProjectIsToldAboutAnItemItOwnsButHasNotOffered() {
        WorkItem offstage = new StubItem("realised", 0.5);
        RecordingProject project = new RecordingProject() {
            @Override
            public boolean owns(WorkItem item) {
                return item == offstage || super.owns(item);
            }
        };
        board.post(project);

        assertTrue(board.claim(offstage, AgentId.random(), 0L), "the board should take the hold");
        assertSame(offstage, project.lastClaimed,
                "a project that owns an item must be told when it is claimed");
    }

    @Test
    void theBoardOffersWhatTheProjectRealises() {
        WorkItem slice = new StubItem("slice 16", 0.5);
        WorkItem trip = new StubItem("trip 48", 0.5);
        RecordingProject project = new RecordingProject() {
            @Override
            public List<WorkItem> open() {
                return List.of(slice);
            }

            @Override
            public WorkItem realise(WorkItem offer, AgentId asker, BrainContext ctx) {
                return trip;
            }

            @Override
            public boolean owns(WorkItem item) {
                return item == trip || super.owns(item);
            }
        };
        Board board = new Board();
        board.post(project);

        assertSame(trip, board.bestFor(AgentId.random(), new BoardBrainContext(), 0L).orElseThrow(),
                "the asker should be handed the realised item, not the offer it scored");
    }

    @Test
    void realisingDoesNotLeaseTheOfferItReplaced() {
        // The arbiter asks every tick and often does not take what it is given. Nothing about the
        // scored offer may change until a real claim lands, or an unclaimed offer accumulates state.
        WorkItem slice = new StubItem("slice 16", 0.5);
        WorkItem trip = new StubItem("trip 48", 0.5);
        RecordingProject project = new RecordingProject() {
            @Override
            public List<WorkItem> open() {
                return List.of(slice);
            }

            @Override
            public WorkItem realise(WorkItem offer, AgentId asker, BrainContext ctx) {
                return trip;
            }
        };
        Board board = new Board();
        board.post(project);

        board.bestFor(AgentId.random(), new BoardBrainContext(), 0L);
        board.bestFor(AgentId.random(), new BoardBrainContext(), 0L);

        assertNull(project.lastClaimed, "asking must never claim");
    }

    @Test
    void outcomesReachTheProjectAndFreeTheItem() {
        FakeProject project = new FakeProject("errands");
        WorkItem done = project.add(new FakeItem("done", 0.5, 0.0));
        WorkItem lost = project.add(new FakeItem("lost", 0.5, 0.0));
        board.post(project);

        board.claim(done, alice, ctx.now());
        board.completed(done, alice, ctx);
        board.claim(lost, alice, ctx.now());
        board.failed(lost, alice, ctx);

        assertEquals(List.of("claimed:done", "completed:done", "claimed:lost", "failed:lost"),
                project.events);
        assertFalse(board.holds(done, alice, ctx.now()), "a finished errand is nobody's");
        assertFalse(board.holds(lost, alice, ctx.now()), "a failed one is nobody's either");
    }

    @Test
    void releasingGivesTheItemBackButOnlyToWhoeverHeldIt() {
        FakeProject project = new FakeProject("errands");
        WorkItem item = project.add(new FakeItem("one", 0.5, 0.0));
        board.post(project);

        board.claim(item, alice, ctx.now());
        board.release(item, bob, ctx.now());
        assertTrue(board.holds(item, alice, ctx.now()), "bob cannot release what he never held");
        board.release(item, alice, ctx.now());
        assertTrue(board.bestFor(bob, ctx, ctx.now()).isPresent(), "released -> back in the pool");
    }

    @Test
    void aFinishedProjectIsClosedAndStopsOffering() {
        FakeProject project = new FakeProject("errands");
        project.add(new FakeItem("one", 0.5, 0.0));
        board.post(project);
        project.finished = true;

        board.closeFinished();
        assertTrue(board.isEmpty(), "satisfied -> dropped");
        assertTrue(board.bestFor(alice, ctx, ctx.now()).isEmpty());
    }

    @Test
    void cancellingByHandleDropsThatProjectAndItsClaims() {
        FakeProject keep = new FakeProject("keep");
        keep.add(new FakeItem("kept", 0.5, 0.0));
        FakeProject drop = new FakeProject("drop");
        WorkItem doomed = drop.add(new FakeItem("doomed", 0.9, 0.0));
        int keepHandle = board.post(keep);
        int dropHandle = board.post(drop);
        board.claim(doomed, alice, ctx.now());

        assertTrue(board.cancel(dropHandle).isPresent());
        assertFalse(board.holds(doomed, alice, ctx.now()), "a cancelled project's claims go with it");
        assertEquals(1, board.projects().size());
        assertTrue(board.cancel(dropHandle).isEmpty(), "handles are never reused");
        assertTrue(board.cancel(keepHandle).isPresent());
    }

    // ---- leases: a hold is a heartbeat, not a lock ---------------------------------------

    /**
     * The failure mode the lease model exists for: a holder never comes back. There is no death hook
     * and no cleanup pass — silence for long enough is letting go, and the errand returns to the
     * pool.
     */
    @Test
    void aHolderWhoGoesQuietLosesTheErrand() {
        FakeProject project = new FakeProject("errands");
        WorkItem item = project.add(new FakeItem("one", 0.5, 0.0));
        board.post(project);
        board.claim(item, alice, 0);

        assertTrue(board.bestFor(bob, ctx, Board.ttlTicks() - 1).isEmpty(), "still hers, just");
        assertSame(item, board.bestFor(bob, ctx, Board.ttlTicks() + 1).orElseThrow(),
                "silence outlived the hold -> anyone's again");
        assertFalse(board.holds(item, alice, Board.ttlTicks() + 1));
    }

    /** ...and saying so keeps it. One heartbeat buys another full TTL, indefinitely. */
    @Test
    void aHeartbeatKeepsTheErrand() {
        FakeProject project = new FakeProject("errands");
        WorkItem item = project.add(new FakeItem("one", 0.5, 0.0));
        board.post(project);
        board.claim(item, alice, 0);

        for (long t = 0; t < Board.ttlTicks() * 3L; t += 10) {
            board.heartbeat(item, alice, t);
        }
        long late = Board.ttlTicks() * 3L;
        assertTrue(board.holds(item, alice, late), "kept saying so -> kept it");
        assertTrue(board.bestFor(bob, ctx, late).isEmpty());
    }

    /** Somebody else's heartbeat is not a claim, and must not renew a hold that is lapsing. */
    @Test
    void aStrangersHeartbeatRenewsNothing() {
        FakeProject project = new FakeProject("errands");
        WorkItem item = project.add(new FakeItem("one", 0.5, 0.0));
        board.post(project);
        board.claim(item, alice, 0);

        board.heartbeat(item, bob, Board.ttlTicks() - 1);
        assertFalse(board.holds(item, alice, Board.ttlTicks() + 1), "bob cannot hold it open for her");
    }

    /**
     * A lapse is not a failure. The project hears {@code lapsed} — no outcome, no retry cooldown
     * — because nobody has learned the errand is undoable, only that its holder stopped saying
     * they were on it.
     */
    @Test
    void expiryTellsTheProjectItLapsedRatherThanFailed() {
        FakeProject project = new FakeProject("errands");
        WorkItem item = project.add(new FakeItem("one", 0.5, 0.0));
        board.post(project);
        board.claim(item, alice, 0);

        board.expire(Board.ttlTicks() - 1);
        assertEquals(List.of("claimed:one"), project.events, "still live: nothing to report");
        board.expire(Board.ttlTicks() + 1);
        assertEquals(List.of("claimed:one", "lapsed:one"), project.events);
    }

    /** A live hold refuses a second taker; a lapsed one is not a conflict but an opening. */
    @Test
    void aSecondTakerIsRefusedOnlyWhileTheHoldIsLive() {
        FakeProject project = new FakeProject("errands");
        WorkItem item = project.add(new FakeItem("one", 0.5, 0.0));
        board.post(project);

        assertTrue(board.claim(item, alice, 0));
        assertFalse(board.claim(item, bob, 1), "hers, and she is still saying so");
        assertTrue(board.claim(item, bob, Board.ttlTicks() + 1), "lapsed -> his to take");
        assertTrue(board.holds(item, bob, Board.ttlTicks() + 1));
        assertFalse(board.holds(item, alice, Board.ttlTicks() + 1));
    }

    /** The dump the claims command prints: live holds only, with who and how long is left. */
    @Test
    void theLeaseDumpReportsLiveHoldsOnly() {
        FakeProject project = new FakeProject("errands");
        WorkItem held = project.add(new FakeItem("held", 0.5, 0.0));
        project.add(new FakeItem("free", 0.5, 0.0));
        board.post(project);
        board.claim(held, alice, 0);

        var live = board.leases(100);
        assertEquals(1, live.size(), "only the one that is actually held");
        assertEquals(alice, live.get(0).who());
        assertEquals("held", live.get(0).item());
        assertEquals(Board.ttlTicks() - 100, live.get(0).remaining());
        assertTrue(board.leases(Board.ttlTicks() + 1).isEmpty(), "lapsed holds are not holds");
    }

    /** The view is the whole identity story: the brain holds one and never learns whose. */
    @Test
    void theViewClaimsAsItsOwnMember() {
        FakeProject project = new FakeProject("errands");
        project.add(new FakeItem("one", 0.5, 0.0));
        board.post(project);
        WorkSource hers = board.viewFor(() -> alice);
        WorkSource his = board.viewFor(() -> bob);

        WorkItem item = hers.bestAvailable(ctx).orElseThrow();
        hers.claimed(item, ctx);
        assertTrue(board.holds(item, alice, ctx.now()));
        assertTrue(his.bestAvailable(ctx).isEmpty(), "one board, one pool: bob sees it is taken");
    }

    // ---- doubles ------------------------------------------------------------------------

    /** A project that offers whatever it is handed and records what the board tells it. */
    private static final class FakeProject implements Project {
        private final String name;
        private final List<WorkItem> items = new ArrayList<>();
        final List<String> events = new ArrayList<>();
        boolean finished;
        /** What a test controls of {@link #offerableTo} — every item to everybody, by default. */
        java.util.function.BiPredicate<WorkItem, AgentId> allow = (item, asker) -> true;

        FakeProject(String name) {
            this.name = name;
        }

        WorkItem add(WorkItem item) {
            items.add(item);
            return item;
        }

        @Override
        public double priority() {
            return 0.5;
        }

        @Override
        public List<WorkItem> open() {
            return List.copyOf(items);
        }

        @Override
        public boolean finished() {
            return finished;
        }

        @Override
        public void claimed(WorkItem item) {
            events.add("claimed:" + item.describe());
        }

        @Override
        public boolean offerableTo(WorkItem item, AgentId asker, BrainContext ctx) {
            return allow.test(item, asker);
        }

        @Override
        public void lapsed(WorkItem item) {
            events.add("lapsed:" + item.describe());
        }

        @Override
        public void completed(WorkItem item, BrainContext ctx) {
            events.add("completed:" + item.describe());
        }

        @Override
        public void failed(WorkItem item, BrainContext ctx) {
            events.add("failed:" + item.describe());
        }

        @Override
        public String describe() {
            return name;
        }
    }

    /** An item with a fixed bid and a fixed cost — no map involved. */
    private record FakeItem(String name, double priority, double cost) implements WorkItem {
        @Override
        public Task root() {
            throw new UnsupportedOperationException("no test here runs the work");
        }

        @Override
        public double estimatedCost(BrainContext ctx) {
            return cost;
        }

        @Override
        public String describe() {
            return name;
        }
    }

    /** An item that prices itself by how far the asker is standing from it. */
    private record PlacedItem(String name, double priority, Pos where) implements WorkItem {
        @Override
        public Task root() {
            throw new UnsupportedOperationException("no test here runs the work");
        }

        @Override
        public double estimatedCost(BrainContext ctx) {
            Pos me = ctx.percepts().position();
            double dx = me.x() - where.x();
            double dz = me.z() - where.z();
            return Math.sqrt(dx * dx + dz * dz) / 100.0;
        }

        @Override
        public String describe() {
            return name;
        }
    }

    /** A work item with nothing behind it — the board only ever scores and leases. */
    private record StubItem(String label, double priority) implements WorkItem {
        @Override
        public Task root() {
            throw new UnsupportedOperationException("never run in a board test");
        }

        @Override
        public String describe() {
            return label;
        }
    }

    /** A project that records what the board told it, and offers nothing by default. */
    private static class RecordingProject implements Project {
        WorkItem lastClaimed;
        AgentId lastClaimant;

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
        public void claimed(WorkItem item) {
            this.lastClaimed = item;
        }

        @Override
        public void completed(WorkItem item, BrainContext ctx) {
        }

        @Override
        public void failed(WorkItem item, BrainContext ctx) {
        }

        @Override
        public String describe() {
            return "recording";
        }
    }
}
