package dev.luizloyola.autarkia.core.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.board.WorkItem;
import dev.luizloyola.anima.core.brain.knowledge.AgentKnowledge;
import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.FakeContext;
import dev.luizloyola.anima.core.brain.task.Follow;
import dev.luizloyola.anima.core.brain.task.GoTo;
import dev.luizloyola.anima.core.brain.task.LookRound;
import dev.luizloyola.anima.core.brain.task.WaitForCompany;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.core.social.PartyId;
import dev.luizloyola.autarkia.core.direction.Direction;
import dev.luizloyola.autarkia.core.direction.DirectionId;
import dev.luizloyola.autarkia.core.direction.Evolution;
import dev.luizloyola.autarkia.core.direction.Home;
import dev.luizloyola.autarkia.core.direction.HomeJudge.Candidate;
import dev.luizloyola.autarkia.core.direction.HomeJudge.Judgement;
import dev.luizloyola.autarkia.core.direction.HomeJudge.Line;
import dev.luizloyola.autarkia.core.direction.HomeJudge.Want;
import dev.luizloyola.autarkia.core.direction.HomeLine;
import dev.luizloyola.autarkia.core.direction.HomeLooking;
import dev.luizloyola.autarkia.core.direction.HomeSearch;
import dev.luizloyola.autarkia.core.direction.HomeSearch.Phase;
import dev.luizloyola.autarkia.core.direction.Lines;
import dev.luizloyola.autarkia.core.direction.Node;
import dev.luizloyola.autarkia.core.direction.NodeKind;
import dev.luizloyola.autarkia.core.direction.PartyProgress;
import dev.luizloyola.autarkia.core.direction.PartyView;
import dev.luizloyola.autarkia.core.direction.Requirements;
import dev.luizloyola.autarkia.core.direction.Tree;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The search on a party's board: a scout looks, walks, looks again and settles, one item at a time;
 * its step is its own across a restart; a refused plot is looked for again; and the home line posts
 * the search only while the party has no HOME.
 */
class ExploreTest {

    private final PartyId party = PartyId.random();

    /** A world that answers each look with the next of the plots queued, the ways all open. */
    private static final class Stub implements HomeLooking {
        final Deque<List<Candidate>> looks = new ArrayDeque<>();
        final List<Candidate> claimed = new ArrayList<>();
        boolean allow = true;

        @Override
        public Optional<Look> look(Pos at, PartyId party, AgentKnowledge knowledge) {
            List<Way> ways = new ArrayList<>();
            for (int k = 0; k < HomeSearch.HEADINGS; k++) {
                double[] d = HomeSearch.direction(k);
                ways.add(new Way(0.5, new Pos(at.x() + (int) Math.round(d[0] * 64), 64,
                        at.z() + (int) Math.round(d[1] * 64)), Set.of()));
            }
            List<Candidate> ranked = looks.isEmpty() ? List.of() : looks.poll();
            return Optional.of(new Look(new Judgement(ranked, Map.of()), ways));
        }

        @Override
        public boolean claim(PartyId party, Candidate plot, AgentKnowledge knowledge) {
            claimed.add(plot);
            return allow;
        }
    }

    private static Candidate plot(int x, int z, double value) {
        return new Candidate(x, z, 63, 17, value, List.of(new Line(Want.WATER, 4, 40)));
    }

    @AfterEach
    void uninstall() {
        Explore.install(null);
        Lines.clear();
    }

    /** The scout's step: first on offer, before the offer to keep with the scout. */
    private static WorkItem only(Explore explore) {
        List<WorkItem> open = explore.open();
        assertFalse(open.isEmpty(), explore.describe());
        return open.get(0);
    }

    @Test
    void aScoutLooksWalksAndSettles() {
        Stub world = new Stub();
        world.looks.add(List.of(plot(10, 0, 50)));
        world.looks.add(List.of(plot(70, 70, 90)));
        Explore.install(world);
        Explore explore = new Explore(party, 0.5);
        FakeContext ctx = new FakeContext();

        WorkItem look = only(explore);
        assertInstanceOf(LookRound.class, look.root());
        explore.claimed(look, ctx.self);
        explore.completed(look, ctx);

        WorkItem walk = only(explore);
        GoTo leg = assertInstanceOf(GoTo.class, walk.root());
        ctx.percepts.position = new Pos(leg.x(), leg.y(), leg.z());
        explore.completed(walk, ctx);
        explore.completed(only(explore), ctx); // the second look: 90 clears 76

        GoTo toPlot = assertInstanceOf(GoTo.class, only(explore).root());
        assertEquals(70, toPlot.x());
        assertEquals(70, toPlot.z());
        explore.completed(only(explore), ctx);

        assertTrue(explore.finished());
        assertEquals(List.of(plot(70, 70, 90)), world.claimed);
    }

    @Test
    void theStepIsTheScoutsAcrossARestart() {
        Stub world = new Stub();
        world.looks.add(List.of(plot(10, 0, 50)));
        Explore.install(world);
        Explore explore = new Explore(party, 0.5);
        FakeContext ctx = new FakeContext();
        WorkItem look = only(explore);
        explore.claimed(look, ctx.self);
        explore.completed(look, ctx);
        WorkItem walk = only(explore);
        WorkKey key = explore.keyOf(walk).orElseThrow();

        Explore restored = Explore.restore((Explore.State) explore.snapshot(), 0).orElseThrow();

        assertEquals(new WorkKey.ForMember(WorkKey.EXPLORE, ctx.self), key);
        WorkItem again = restored.itemFor(key).orElseThrow();
        GoTo before = (GoTo) walk.root();
        GoTo after = (GoTo) again.root();
        assertEquals(List.of(before.x(), before.y(), before.z()), List.of(after.x(), after.y(), after.z()),
                "a restored scout walks on to the same leg's end");
        assertTrue(restored.itemFor(new WorkKey.ForMember(WorkKey.EXPLORE,
                dev.luizloyola.anima.core.agent.AgentId.random())).isEmpty());
    }

    @Test
    void aRefusedPlotIsLookedForAgain() {
        Stub world = new Stub();
        world.looks.add(List.of(plot(10, 0, 90)));
        world.allow = false;
        Explore.install(world);
        Explore explore = new Explore(party, 0.5);
        FakeContext ctx = new FakeContext();
        explore.completed(only(explore), ctx); // settle at once
        explore.completed(only(explore), ctx); // and the claim is refused

        assertFalse(explore.finished());
        assertEquals(Phase.LOOK, explore.search().phase());
        assertInstanceOf(LookRound.class, only(explore).root());
    }

    @Test
    void aFailedLegNearTheStopTriesAnotherWay() {
        Stub world = new Stub();
        Explore.install(world);
        Explore explore = new Explore(party, 0.5);
        FakeContext ctx = new FakeContext();
        explore.completed(only(explore), ctx);
        WorkItem walk = only(explore);
        GoTo first = (GoTo) walk.root();

        explore.failed(walk, ctx.self, ctx);

        GoTo second = assertInstanceOf(GoTo.class, only(explore).root());
        assertFalse(first.x() == second.x() && first.z() == second.z());
    }

    @Test
    void theHomeLinePostsASearchOnlyWithoutAHome() {
        Lines.register(HomeLine.INSTANCE);
        String wood = "autarkia:wood";
        Node node = new Node(wood, NodeKind.CORE, List.of(), true, Requirements.NONE,
                List.of(new Direction(new DirectionId(wood, "home"), 0, null)), Set.of(), Set.of());
        Tree tree = Tree.build(List.of(node), Set.of(), key -> false).tree();
        PartyProgress progress = new PartyProgress();
        PartyBoard board = new PartyBoard(party);
        PartyView view = new PartyView() {
            @Override
            public PartyId party() {
                return party;
            }

            @Override
            public Optional<Home> home() {
                return Optional.ofNullable(progress.home());
            }

            @Override
            public int members() {
                return 1;
            }

            @Override
            public OptionalInt storedAtHome(ItemSpec spec) {
                return OptionalInt.empty();
            }

            @Override
            public boolean hasAtHome(PoiKind kind) {
                return false;
            }

            @Override
            public OptionalInt freeSlotsAtHome() {
                return OptionalInt.empty();
            }

            @Override
            public OptionalInt foodAtHome() {
                return OptionalInt.empty();
            }
        };

        Evolution.Outcome first = Evolution.beat(tree, progress, view, board);
        assertEquals(1, first.posted().size());
        assertInstanceOf(Explore.class, first.posted().get(0).project());
        assertTrue(Evolution.beat(tree, progress, view, board).posted().isEmpty(), "posted once");

        progress.home(Home.at(new Pos(0, 64, 0)));
        Evolution.Outcome met = Evolution.beat(tree, progress, view, board);

        assertEquals(1, met.withdrawn().size(), "an operator's HOME ends the search");
    }

    @Test
    void companionsKeepWithTheScoutAndTheScoutWaitsForThem() {
        Stub world = new Stub();
        Explore.install(world);
        Explore explore = new Explore(party, 0.5);
        FakeContext scout = new FakeContext();
        FakeContext companion = new FakeContext();
        WorkItem look = only(explore);
        explore.claimed(look, scout.self);
        explore.completed(look, scout);

        List<WorkItem> open = explore.open();
        assertEquals(2, open.size(), "the leg, and the offer to keep with the scout");
        WorkItem offer = open.get(1);
        assertFalse(explore.offerableTo(offer, scout.self, scout), "the scout does not follow itself");
        assertFalse(explore.offerableTo(open.get(0), companion.self, companion),
                "nor does a companion take the scout's step");
        WorkItem mine = explore.realise(offer, companion.self, companion);
        explore.claimed(mine, companion.self);
        Follow follow = assertInstanceOf(Follow.class, mine.root());
        assertEquals(dev.luizloyola.anima.core.brain.sense.BeingId.of(scout.self), follow.leader());
        assertEquals(new WorkKey.ForMember(WorkKey.ACCOMPANY, companion.self),
                explore.keyOf(mine).orElseThrow());

        explore.completed(mine, companion);
        assertEquals(2, explore.open().size(), "together at a stop, the companion takes the offer again");

        GoTo leg = (GoTo) open.get(0).root();
        scout.percepts.position = new Pos(leg.x(), leg.y(), leg.z());
        explore.completed(open.get(0), scout);

        WaitForCompany wait = assertInstanceOf(WaitForCompany.class, explore.open().get(0).root());
        assertEquals(java.util.Set.of(dev.luizloyola.anima.core.brain.sense.BeingId.of(companion.self)),
                wait.whom());
        explore.completed(explore.open().get(0), scout);
        assertInstanceOf(LookRound.class, explore.open().get(0).root(), "then it looks round");

        Explore restored = Explore.restore((Explore.State) explore.snapshot(), 0).orElseThrow();
        assertInstanceOf(LookRound.class, restored.open().get(0).root(), "the party stays gathered");
    }

    @Test
    void aStepTheScoutLeavesGoesToSomebodyElse() {
        Explore.install(new Stub());
        Explore explore = new Explore(party, 0.5);
        FakeContext scout = new FakeContext();
        FakeContext other = new FakeContext();
        WorkItem look = only(explore);
        explore.claimed(look, scout.self);
        explore.completed(look, scout);
        WorkItem walk = explore.open().get(0);

        explore.tick(Explore.SCOUT_LAPSE / 2);
        assertFalse(explore.offerableTo(walk, other.self, other));
        explore.tick(Explore.SCOUT_LAPSE + 1);
        assertTrue(explore.offerableTo(walk, other.self, other), "the scout left it too long");
    }

    @Test
    void aCompanionLeftBehindAtAnOldMeetingPlaceGoesOnToTheNew() {
        Explore.install(new Stub());
        Explore explore = new Explore(party, 0.5);
        FakeContext scout = new FakeContext();
        FakeContext companion = new FakeContext();
        WorkItem look = only(explore);
        explore.claimed(look, scout.self);
        explore.completed(look, scout);
        WorkItem walk = explore.open().get(0);
        GoTo leg = (GoTo) walk.root();
        scout.percepts.position = new Pos(leg.x(), leg.y(), leg.z());
        explore.completed(walk, scout);
        WorkItem mine = explore.realise(explore.open().get(1), companion.self, companion);
        explore.claimed(mine, companion.self);
        mine.root(); // given the stop the scout stands at
        explore.completed(explore.open().get(0), scout); // the wait
        explore.completed(explore.open().get(0), scout); // the look: off on the next leg

        explore.failed(mine, companion.self, companion);

        assertTrue(explore.offerableTo(explore.open().get(1), companion.self, companion),
                "a new leg's end to meet at, so no cooldown");
    }
}
