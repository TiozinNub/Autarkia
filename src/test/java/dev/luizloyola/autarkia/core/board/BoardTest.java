package dev.luizloyola.autarkia.core.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

        assertSame(cheap, board.bestFor(alice, ctx).orElseThrow(),
                "same bid, lower cost -> the one that costs this asker less");
        board.claim(cheap, alice);
        assertSame(dear, board.bestFor(bob, ctx).orElseThrow(),
                "the claimed one is gone from the pool; the next best is offered");
        board.claim(dear, bob);
        assertTrue(board.bestFor(alice, ctx).isEmpty(), "everything is spoken for");
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
        assertSame(east, board.bestFor(alice, ctx).orElseThrow());
        ctx.standAt(new Pos(-18, 0, 0));
        assertSame(west, board.bestFor(bob, ctx).orElseThrow());
    }

    @Test
    void anAgentWithNoIdentityYetIsOfferedNothing() {
        FakeProject project = new FakeProject("errands");
        project.add(new FakeItem("anything", 0.5, 0.0));
        board.post(project);
        assertTrue(board.bestFor(null, ctx).isEmpty(),
                "a body that does not know who it is cannot owe anybody an errand");
    }

    @Test
    void aClaimTellsTheProjectItsItemIsSpokenFor() {
        FakeProject project = new FakeProject("errands");
        WorkItem item = project.add(new FakeItem("one", 0.5, 0.0));
        board.post(project);
        board.claim(item, alice);
        assertEquals(List.of("claimed:one"), project.events);
    }

    @Test
    void outcomesReachTheProjectAndFreeTheItem() {
        FakeProject project = new FakeProject("errands");
        WorkItem done = project.add(new FakeItem("done", 0.5, 0.0));
        WorkItem lost = project.add(new FakeItem("lost", 0.5, 0.0));
        board.post(project);

        board.claim(done, alice);
        board.completed(done, alice, ctx);
        board.claim(lost, alice);
        board.failed(lost, alice, ctx);

        assertEquals(List.of("claimed:done", "completed:done", "claimed:lost", "failed:lost"),
                project.events);
        assertFalse(board.holds(done, alice), "a finished errand is nobody's");
        assertFalse(board.holds(lost, alice), "a failed one is nobody's either");
    }

    @Test
    void releasingGivesTheItemBackButOnlyToWhoeverHeldIt() {
        FakeProject project = new FakeProject("errands");
        WorkItem item = project.add(new FakeItem("one", 0.5, 0.0));
        board.post(project);

        board.claim(item, alice);
        board.release(item, bob);
        assertTrue(board.holds(item, alice), "bob cannot release what he never held");
        board.release(item, alice);
        assertTrue(board.bestFor(bob, ctx).isPresent(), "released -> back in the pool");
    }

    @Test
    void aFinishedProjectIsClosedAndStopsOffering() {
        FakeProject project = new FakeProject("errands");
        project.add(new FakeItem("one", 0.5, 0.0));
        board.post(project);
        project.finished = true;

        board.closeFinished();
        assertTrue(board.isEmpty(), "satisfied -> dropped");
        assertTrue(board.bestFor(alice, ctx).isEmpty());
    }

    @Test
    void cancellingByHandleDropsThatProjectAndItsClaims() {
        FakeProject keep = new FakeProject("keep");
        keep.add(new FakeItem("kept", 0.5, 0.0));
        FakeProject drop = new FakeProject("drop");
        WorkItem doomed = drop.add(new FakeItem("doomed", 0.9, 0.0));
        int keepHandle = board.post(keep);
        int dropHandle = board.post(drop);
        board.claim(doomed, alice);

        assertTrue(board.cancel(dropHandle).isPresent());
        assertFalse(board.holds(doomed, alice), "a cancelled project's claims go with it");
        assertEquals(1, board.projects().size());
        assertTrue(board.cancel(dropHandle).isEmpty(), "handles are never reused");
        assertTrue(board.cancel(keepHandle).isPresent());
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
        assertTrue(board.holds(item, alice));
        assertTrue(his.bestAvailable(ctx).isEmpty(), "one board, one pool: bob sees it is taken");
    }

    // ---- doubles ------------------------------------------------------------------------

    /** A project that offers whatever it is handed and records what the board tells it. */
    private static final class FakeProject implements Project {
        private final String name;
        private final List<WorkItem> items = new ArrayList<>();
        final List<String> events = new ArrayList<>();
        boolean finished;

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
}
