package dev.luizloyola.autarkia.core.direction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.FoodValue;
import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.Food;
import dev.luizloyola.anima.core.brain.task.FakeContext;
import dev.luizloyola.anima.core.brain.task.RawFood;
import dev.luizloyola.anima.core.brain.task.ReadyFood;
import dev.luizloyola.anima.core.craft.Campfire;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.core.social.PartyId;
import dev.luizloyola.anima.core.territory.ChunkKey;
import dev.luizloyola.autarkia.core.board.Cook;
import dev.luizloyola.autarkia.core.board.Gather;
import dev.luizloyola.autarkia.core.board.PartyBoard;
import dev.luizloyola.autarkia.core.board.Project;
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
 * A food line whose work gets nowhere tries another way, and goes back once food reaches HOME.
 * Forest, 2026-10-02: Hannah's line had one cook posted for hours, failing, and posted nothing else.
 */
class FoodLineStuckTest {

    private static final String WOOD = "test:wood";
    private static final DirectionId FOOD = new DirectionId(WOOD, "food");
    private static final Pos SPOT = new Pos(0, 64, 0);
    private static final Pos FIRE = new Pos(2, 64, 1);

    private final PartyId partyId = PartyId.of(new UUID(7, 7));
    private final PartyBoard board = new PartyBoard(partyId);
    private final PartyProgress progress = new PartyProgress();
    private final Home view = new Home();
    private Tree tree;
    /** Whether somebody holds the line's work at the beat. */
    private boolean worked;

    /** One settler, a campfire, a mutton HOME holds, and the stall read off the progress as HOME's view reads it. */
    private final class Home implements PartyView {
        int points = 3;

        @Override
        public PartyId party() {
            return partyId;
        }

        @Override
        public Optional<dev.luizloyola.autarkia.core.direction.Home> home() {
            return Optional.ofNullable(progress.home());
        }

        @Override
        public SortedSet<ChunkKey> area() {
            return new TreeSet<>(Set.of(ChunkKey.at(ChunkKey.OVERWORLD, 0, 0)));
        }

        @Override
        public Optional<Pos> spot() {
            return Optional.of(SPOT);
        }

        @Override
        public int members() {
            return 1;
        }

        @Override
        public OptionalInt storedAtHome(ItemSpec spec) {
            return OptionalInt.of(spec == RawFood.SPEC ? 1 : spec == Food.SPEC ? 1 : 0);
        }

        @Override
        public OptionalInt foodAtHome() {
            return OptionalInt.of(points);
        }

        @Override
        public boolean stuck(DirectionId direction) {
            return progress.stall(direction).map(PartyProgress.Stall::stuck).orElse(false);
        }

        @Override
        public boolean hasAtHome(PoiKind kind) {
            return true;
        }

        @Override
        public Optional<Pos> placeAtHome(PoiKind kind) {
            return kind == Campfire.POI ? Optional.of(FIRE) : Optional.empty();
        }

        @Override
        public OptionalInt freeSlotsAtHome() {
            return OptionalInt.of(27);
        }
    }

    @BeforeEach
    void wood() {
        // The line's load check asks that the node open some food, which the lookup names.
        FakeContext foods = new FakeContext();
        foods.percepts.food("minecraft:mutton", new FoodValue(2, 1.2F, false));
        foods.percepts.cooked("minecraft:mutton", new FoodValue(6, 9.6F, false));
        ReadyFood.install(foods.percepts.foods());
        Lines.register(FoodLine.INSTANCE);
        Node wood = new Node(WOOD, NodeKind.CORE, List.of(), true, Requirements.NONE,
                List.of(new Direction(FOOD, 32, null)), Set.of("minecraft:mutton"), Set.of());
        tree = Tree.build(List.of(wood), Set.of("minecraft:mutton"), key -> false).tree();
        progress.home(dev.luizloyola.autarkia.core.direction.Home.fresh());
    }

    @AfterEach
    void clear() {
        Lines.clear();
        ReadyFood.install(null);
    }

    private void beats(int n) {
        for (int i = 0; i < n; i++) {
            Evolution.beat(tree, progress, view, board, project -> worked);
        }
    }

    private Project work() {
        assertEquals(1, board.projects().size(), "one piece of work at a time: " + board.projects());
        return board.projects().get(0);
    }

    @Test
    void aCookGettingNowhereGivesWayToAGatherAndComesBackWithFood() {
        beats(1);
        assertInstanceOf(Cook.class, work());

        beats(14); // 2,800 ticks: a failure's 600-tick wait and the next one's 1,200 both fit
        assertInstanceOf(Cook.class, work(), "a cook waiting a while for a taker is not stuck");

        beats(1);
        Gather gather = assertInstanceOf(Gather.class, work(),
                "fifteen beats with nobody on it and no food gained: forage, else hunt");
        assertEquals(Food.SPEC, gather.spec());

        beats(3 * PartyProgress.Stall.STUCK_BEATS);
        assertTrue(work() == gather, "and it stays: only food reaching HOME ends a stuck line, so it never flaps");

        view.points = 11; // the gather brought a sheep's worth home
        beats(1);
        assertInstanceOf(Cook.class, work(), "back to cooking what HOME holds raw");
    }

    @Test
    void aBeatWithSomebodyOnTheWorkDoesNotCount() {
        beats(1);
        worked = true;
        beats(4 * PartyProgress.Stall.STUCK_BEATS);

        assertInstanceOf(Cook.class, work(), "a cook at the fire is getting somewhere though HOME's food went down");
        view.points = 1; // the mutton went from the chest to the fire
        beats(PartyProgress.Stall.STUCK_BEATS);
        assertInstanceOf(Cook.class, work());
    }

    @Test
    void aStallComesBackFromTheProgressItWasSavedIn() {
        beats(PartyProgress.Stall.STUCK_BEATS);
        PartyProgress.Stall saved = progress.stall(FOOD).orElseThrow();
        assertEquals(PartyProgress.Stall.STUCK_BEATS - 1, saved.beats());

        progress.stall(FOOD, null);
        progress.stall(FOOD, saved);
        beats(1);
        assertInstanceOf(Gather.class, work(), "the count picked up where it was");
    }
}
