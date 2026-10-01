package dev.luizloyola.autarkia.core.direction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.core.social.PartyId;
import dev.luizloyola.anima.core.territory.ChunkKey;
import dev.luizloyola.autarkia.core.board.Fellings;
import dev.luizloyola.autarkia.core.board.PartyBoard;
import dev.luizloyola.autarkia.core.board.Project;
import dev.luizloyola.autarkia.core.board.SetUp;
import dev.luizloyola.autarkia.core.tree.TreeFelling;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The base comes first: nothing hauls to a HOME with nowhere to put anything, what the base lacks
 * is posted once as one job, and running short of room is the base's question, asked one chest at a
 * time.
 */
class BaseAndStorageTest {

    private static final String WOOD = "test:wood";
    /** Where the party's next station goes; HOME is its chunk. */
    private static final Pos SPOT = new Pos(10, 64, 10);

    private final PartyId partyId = PartyId.of(new UUID(7, 7));
    private final PartyBoard board = new PartyBoard(partyId);
    private final PartyProgress progress = new PartyProgress();
    private final Base view = new Base();
    private Tree tree;

    /** What HOME has and how much room is left, set by each test. */
    private final class Base implements PartyView {

        /** A HOME whose chunks were never claimed. */
        boolean noArea;

        @Override
        public SortedSet<ChunkKey> area() {
            return noArea ? new TreeSet<>()
                    : new TreeSet<>(Set.of(ChunkKey.at(ChunkKey.OVERWORLD, SPOT.x(), SPOT.z())));
        }

        @Override
        public Optional<Pos> spot() {
            return noArea ? Optional.empty() : Optional.of(SPOT);
        }
        final Set<PoiKind> stations = new HashSet<>();
        OptionalInt free = OptionalInt.of(27);

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
            return 3;
        }

        @Override
        public OptionalInt storedAtHome(ItemSpec spec) {
            return OptionalInt.of(0);
        }

        @Override
        public boolean hasAtHome(PoiKind kind) {
            return stations.contains(kind);
        }

        @Override
        public OptionalInt freeSlotsAtHome() {
            return free;
        }

        @Override
        public OptionalInt foodAtHome() {
            return OptionalInt.of(0);
        }
    }

    @BeforeEach
    void wood() {
        Lines.register(AreaLine.INSTANCE);
        Lines.register(WoodLine.INSTANCE);
        Lines.register(BaseLine.INSTANCE);
        Lines.register(StorageLine.INSTANCE);
        Fellings.register(TreeFelling.INSTANCE);
        Node wood = new Node(WOOD, NodeKind.CORE, List.of(), true, Requirements.NONE,
                List.of(new Direction(new DirectionId(WOOD, "base"), 0, null),
                        new Direction(new DirectionId(WOOD, "area"), 0, null),
                        new Direction(new DirectionId(WOOD, "wood"), 64, null),
                        new Direction(new DirectionId(WOOD, "storage"), 9, null)),
                Set.of(), Set.of());
        tree = Tree.build(List.of(wood), Set.of("minecraft:oak_log"), key -> false).tree();
        progress.home(Home.fresh());
    }

    @AfterEach
    void clear() {
        Lines.clear();
        Fellings.clear();
    }

    private Evolution.Outcome beat() {
        return Evolution.beat(tree, progress, view, board);
    }

    private static List<String> lines(Evolution.Outcome outcome) {
        return outcome.posted().stream().map(p -> p.direction().line()).toList();
    }

    @Test
    void withNoBaseOnlyTheBaseIsPosted() {
        Evolution.Outcome outcome = beat();

        assertEquals(List.of("base"), lines(outcome),
                "the clearing and the gather wait: nothing hauls to a HOME with nowhere to put it");
        SetUp setUp = assertInstanceOf(SetUp.class, board.projects().get(0));
        assertEquals(List.of(SetUp.WORKBENCH, SetUp.STORE), setUp.stations(),
                "the workbench first, so the chest is crafted at HOME's own table");
        assertEquals(SPOT, setUp.near());
        assertEquals(1, setUp.open().size(), "one station on offer at a time — one builder");
    }

    @Test
    void aHomeWithNoAreaPostsNoBase() {
        view.noArea = true;

        assertTrue(beat().posted().isEmpty(),
                "with no chunks there is no spot for a station, and posting would throw");
    }

    @Test
    void theBaseIsPostedOnceNeverOncePerBeat() {
        beat();
        assertTrue(beat().posted().isEmpty());
        assertEquals(1, board.projects().size());
    }

    @Test
    void aBaseMissingOnlyItsChestAsksForOnlyTheChest() {
        view.stations.add(SetUp.WORKBENCH.kind());
        beat();
        SetUp setUp = assertInstanceOf(SetUp.class, board.projects().get(0));
        assertEquals(List.of(SetUp.STORE), setUp.stations());
    }

    @Test
    void onceTheBaseStandsTheWorkThatHaulsToItGoesUp() {
        view.stations.add(SetUp.WORKBENCH.kind());
        view.stations.add(SetUp.STORE.kind());
        Evolution.Outcome outcome = beat();

        assertTrue(outcome.completed().contains(new DirectionId(WOOD, "base")));
        assertEquals(List.of("area", "wood"), lines(outcome));
    }

    @Test
    void runningShortOfRoomAsksForOneChestAndTheBaseLeavesItAlone() {
        view.stations.add(SetUp.WORKBENCH.kind());
        view.stations.add(SetUp.STORE.kind());
        view.free = OptionalInt.of(3);

        Evolution.Outcome first = beat();
        assertTrue(lines(first).contains("storage"));
        SetUp more = board.projects().stream().filter(p -> p instanceof SetUp).map(p -> (SetUp) p)
                .findFirst().orElseThrow();
        assertEquals(List.of(SetUp.STORE), more.stations());

        Evolution.Outcome second = beat();
        assertTrue(second.posted().isEmpty(), "one more chest, not one per beat");
        assertTrue(second.withdrawn().isEmpty(),
                "the base is met and must not claim storage's chest as its own work — it would "
                        + "withdraw it every beat while storage posted it again");
        assertTrue(board.projects().contains(more));
    }

    @Test
    void storageWaitsForTheBase() {
        view.free = OptionalInt.of(0);
        Evolution.Outcome outcome = beat();
        assertEquals(List.of("base"), lines(outcome));
        Status storage = StorageLine.INSTANCE.judge(
                new Direction(new DirectionId(WOOD, "storage"), 9, null), view);
        assertEquals(Status.NO_BASE, storage);
    }
}
