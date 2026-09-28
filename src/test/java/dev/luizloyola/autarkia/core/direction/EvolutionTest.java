package dev.luizloyola.autarkia.core.direction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.brain.knowledge.Region;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.core.social.PartyId;
import dev.luizloyola.autarkia.core.board.ClearArea;
import dev.luizloyola.autarkia.core.board.Clearings;
import dev.luizloyola.autarkia.core.board.Gather;
import dev.luizloyola.autarkia.core.board.PartyBoard;
import dev.luizloyola.autarkia.core.board.Project;
import dev.luizloyola.autarkia.core.tree.TreeClearing;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A party climbing from Wood to Stone one beat at a time, against a real board: work posted once
 * and never twice, withdrawn when the world already holds, a clearing heard finishing after the
 * board has closed it, and the checkpoints that make Stone.
 */
class EvolutionTest {

    private static final String WOOD = "test:wood";
    private static final String STONE = "test:stone";
    private static final Region PLOT = new Region(new Pos(0, 60, 0), new Pos(20, 90, 20));
    private static final Pos YARD = new Pos(10, 64, 10);

    private final PartyId partyId = PartyId.of(new UUID(4, 2));
    private final PartyBoard board = new PartyBoard(partyId);
    private final PartyProgress progress = new PartyProgress();
    private final Stores view = new Stores();
    private Tree tree;

    /** HOME as the progress holds it, and a store count the test sets. */
    private final class Stores implements PartyView {
        OptionalInt logs = OptionalInt.of(0);

        @Override
        public PartyId party() {
            return partyId;
        }

        @Override
        public Optional<Home> home() {
            return Optional.ofNullable(progress.home());
        }

        @Override
        public int members() {
            return 2;
        }

        @Override
        public OptionalInt storedAtHome(ItemSpec spec) {
            return logs;
        }

        /** The base stands in these tests: they are about the lines that wait for it. */
        @Override
        public boolean hasAtHome(PoiKind kind) {
            return true;
        }

        @Override
        public OptionalInt freeSlotsAtHome() {
            return OptionalInt.of(27);
        }

        @Override
        public OptionalInt readyFoodAtHome() {
            return OptionalInt.of(0);
        }
    }

    @BeforeEach
    void climb() {
        Lines.register(AreaLine.INSTANCE);
        Lines.register(WoodLine.INSTANCE);
        Clearings.register(TreeClearing.INSTANCE);
        Node wood = new Node(WOOD, NodeKind.CORE, List.of(), true, Requirements.NONE,
                List.of(new Direction(new DirectionId(WOOD, "area"), 0, null),
                        new Direction(new DirectionId(WOOD, "wood"), 64, null)),
                Set.of(), Set.of());
        Node stone = new Node(STONE, NodeKind.CORE, List.of(WOOD), true,
                new Requirements(new DirectionId(WOOD, "wood"), List.of(new DirectionId(WOOD, "area")), 1),
                List.of(new Direction(new DirectionId(STONE, "wood"), 256, null)), Set.of(), Set.of());
        tree = Tree.build(List.of(wood, stone), Set.of("minecraft:oak_log"), key -> false).tree();
        progress.home(new Home(PLOT, YARD, false));
    }

    @AfterEach
    void clear() {
        Lines.clear();
        Clearings.clear();
    }

    private Evolution.Outcome beat() {
        return Evolution.beat(tree, progress, view, board);
    }

    @Test
    void withNoHomeNothingIsPosted() {
        progress.home(null);
        Evolution.Outcome outcome = beat();
        assertTrue(outcome.posted().isEmpty(), "both lines wait for a HOME");
        assertTrue(board.isEmpty());
    }

    @Test
    void unmetWorkIsPostedOnceAndNeverTwice() {
        Evolution.Outcome first = beat();
        assertEquals(2, first.posted().size());
        Gather gather = assertInstanceOf(Gather.class, board.projects().stream()
                .filter(p -> p instanceof Gather).findFirst().orElseThrow());
        assertEquals(64, gather.target());
        assertEquals(YARD, gather.yard());
        assertEquals(Direction.TOWARD_CORE, gather.priority(), "it leads toward the Stone Age");
        ClearArea clearing = assertInstanceOf(ClearArea.class, board.projects().stream()
                .filter(p -> p instanceof ClearArea).findFirst().orElseThrow());
        assertEquals(PLOT, clearing.bounds());

        assertTrue(beat().posted().isEmpty(), "its own work is on the board already");
        assertEquals(2, board.projects().size());
    }

    @Test
    void anOperatorsIdenticalWorkServesAsWell() {
        board.post(new Gather(dev.luizloyola.autarkia.core.board.Stock.LOGS, 500, YARD, 0.5, partyId,
                dev.luizloyola.autarkia.core.board.CarrySplit.INSTANCE));
        Evolution.Outcome outcome = beat();
        assertEquals(List.of("area"), outcome.posted().stream().map(p -> p.direction().line()).toList(),
                "a gather of logs to the yard is already the wood line's work, whoever posted it");
    }

    @Test
    void workTheWorldAlreadyCoveredIsWithdrawn() {
        beat();
        view.logs = OptionalInt.of(70);
        Evolution.Outcome outcome = beat();
        assertEquals(List.of(new DirectionId(WOOD, "wood")), outcome.completed());
        assertEquals(1, outcome.withdrawn().size());
        assertTrue(board.projects().stream().noneMatch(p -> p instanceof Gather));
        assertEquals(List.of(), outcome.reached(), "the pool still wants the plot cleared");
    }

    @Test
    void aStoreOutOfSightChangesNothing() {
        view.logs = OptionalInt.empty();
        Evolution.Outcome outcome = beat();
        assertEquals(List.of("area"), outcome.posted().stream().map(p -> p.direction().line()).toList());
    }

    @Test
    void aClearingHeardFinishingAfterTheBoardClosedItMakesStone() {
        beat();
        view.logs = OptionalInt.of(64);
        beat();
        // The clearing finishes, and the board's own tick closes it and hands it over on that tick
        // — the one moment its ledger still exists. A restart between that close and the next
        // Directions beat used to lose it: the map that remembered it lived in memory.
        ClearArea posted = clearingOnTheBoard();
        board.cancel(board.handleOf(posted).orElseThrow());
        ClearArea done = ClearArea.restore(new ClearArea.State("trees", PLOT, 0.5, ClearArea.Phase.DONE,
                List.of(), List.of(), 0, List.of(), YARD, List.of(), List.of()), 0L).orElseThrow();
        board.post(done);
        assertEquals(List.of(new DirectionId(WOOD, "area")),
                Evolution.collect(tree, progress, view, board.closeFinished()));

        Evolution.Outcome outcome = beat();
        assertTrue(progress.home().cleared(), "the finished clearing is the party's knowledge");
        assertTrue(outcome.completed().contains(new DirectionId(WOOD, "area")));
        assertEquals(List.of(STONE), outcome.reached());
        assertTrue(progress.reached().contains(STONE));

        Evolution.Outcome next = beat();
        assertEquals(1, next.posted().size());
        assertEquals(256, ((Gather) next.posted().get(0).project()).target(),
                "Stone's version of the line is in force now");
        assertEquals(Direction.UPKEEP, next.posted().get(0).project().priority(),
                "and it leads nowhere new in a two-node tree");
    }

    private ClearArea clearingOnTheBoard() {
        return (ClearArea) board.projects().stream().filter(p -> p instanceof ClearArea)
                .findFirst().orElseThrow();
    }

    /**
     * Moving HOME withdraws the work the Directions had out for the old one, found by content —
     * an in-memory map of it was empty after a restart and left the old yard's work running.
     */
    @Test
    void theWorkForAHomeIsFoundByWhatItIs() {
        beat();
        board.post(new Gather(dev.luizloyola.autarkia.core.board.Stock.LOGS, 500,
                new dev.luizloyola.anima.core.brain.sense.Pos(900, 64, 900), 0.5, partyId,
                dev.luizloyola.autarkia.core.board.CarrySplit.INSTANCE));
        List<Project> own = Evolution.ownWork(tree, progress, view, board);
        assertEquals(2, own.size(), "the clearing and the gather for this HOME, not a gather elsewhere");
    }

    @Test
    void aCancelledClearingIsPostedAgain() {
        beat();
        ClearArea posted = clearingOnTheBoard();
        board.cancel(board.handleOf(posted).orElseThrow());
        Evolution.Outcome outcome = beat();
        assertEquals(List.of("area"), outcome.posted().stream().map(p -> p.direction().line()).toList());
        assertTrue(!progress.home().cleared(), "cancelled is not finished");
    }
}
