package dev.luizloyola.autarkia.core.direction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.Food;
import dev.luizloyola.anima.core.brain.task.RawFood;
import dev.luizloyola.anima.core.craft.Campfire;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.core.social.PartyId;
import dev.luizloyola.autarkia.core.board.CarrySplit;
import dev.luizloyola.autarkia.core.board.Cook;
import dev.luizloyola.autarkia.core.board.SetUp;
import dev.luizloyola.autarkia.core.board.Gather;
import dev.luizloyola.autarkia.core.board.Stock;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

/**
 * {@code food}: hunger points in HOME's stores, a per-member goal, and a gather that asks for the
 * shortfall in items.
 */
class FoodLineTest {

    private static final Direction FOOD = new Direction(new DirectionId("test:wood", "food"), 32, null);
    private static final Pos SPOT = new Pos(0, 64, 0);
    private static final Pos FIRE = new Pos(3, 64, 2);

    /** A party of {@link #members} with HOME's stores holding {@link #points} of food as {@link #items}. */
    private static final class Party implements PartyView {
        final PartyId id = PartyId.random();
        int members = 3;
        OptionalInt points = OptionalInt.of(0);
        int items = 0;
        boolean base = true;

        @Override
        public PartyId party() {
            return id;
        }

        @Override
        public Optional<Home> home() {
            return Optional.of(Home.fresh());
        }

        @Override
        public int members() {
            return members;
        }

        int raw = 0;
        int charcoal = 0;
        Optional<Pos> campfire = Optional.empty();

        @Override
        public OptionalInt storedAtHome(ItemSpec spec) {
            return OptionalInt.of(spec == RawFood.SPEC ? raw : spec == CharcoalLine.CHARCOAL ? charcoal : items);
        }

        @Override
        public Optional<Pos> placeAtHome(PoiKind kind) {
            return kind == Campfire.POI ? campfire : Optional.empty();
        }

        @Override
        public Optional<Pos> spot() {
            return Optional.of(SPOT);
        }

        @Override
        public java.util.SortedSet<dev.luizloyola.anima.core.territory.ChunkKey> area() {
            return new java.util.TreeSet<>(java.util.Set.of(dev.luizloyola.anima.core.territory.ChunkKey.at(
                    dev.luizloyola.anima.core.territory.ChunkKey.OVERWORLD, SPOT.x(), SPOT.z())));
        }

        @Override
        public OptionalInt foodAtHome() {
            return points;
        }

        @Override
        public boolean hasAtHome(PoiKind kind) {
            return base;
        }

        @Override
        public OptionalInt freeSlotsAtHome() {
            return OptionalInt.of(27);
        }
    }

    private final Party party = new Party();

    @Test
    void theGoalIsPerMember() {
        party.points = OptionalInt.of(95);
        assertEquals(Status.Reading.UNMET, FoodLine.INSTANCE.judge(FOOD, party).reading(),
                "three members want 96; 95 is one short");

        party.points = OptionalInt.of(96);
        assertEquals(Status.Reading.MET, FoodLine.INSTANCE.judge(FOOD, party).reading());

        party.members = 4;
        assertEquals(Status.Reading.UNMET, FoodLine.INSTANCE.judge(FOOD, party).reading(),
                "a fourth member raises the goal, not the pace");
    }

    @Test
    void itWaitsForTheBase() {
        party.base = false;
        assertEquals(Status.NO_BASE, FoodLine.INSTANCE.judge(FOOD, party));
    }

    @Test
    void aStoreThatCouldNotBeReadIsNotAnEmptyOne() {
        party.points = OptionalInt.empty();
        assertEquals(Status.UNREAD, FoodLine.INSTANCE.judge(FOOD, party));
    }

    @Test
    void theGatherAsksForWhatIsThereAndTheShortfallInItems() {
        party.points = OptionalInt.of(40); // ten apples, say
        party.items = 10;

        Gather gather = assertInstanceOf(Gather.class, FoodLine.INSTANCE.post(FOOD, party, 0.4));

        assertEquals(Food.SPEC, gather.spec());
        assertEquals(10 + 28, gather.target(),
                "56 points short at two a berry is 28 more items on top of the 10 there");
    }

    @Test
    void itKnowsItsOwnGatherAndNoOther() {
        Gather food = assertInstanceOf(Gather.class, FoodLine.INSTANCE.post(FOOD, party, 0.4));
        Gather logs = new Gather(Stock.LOGS, 64, 0.4, party.id, CarrySplit.INSTANCE);

        assertTrue(FoodLine.INSTANCE.isWork(food, FOOD, party));
        assertFalse(FoodLine.INSTANCE.isWork(logs, FOOD, party));
    }

    @Test
    void rawFoodAtHomeIsCookedBeforeAnybodyIsSentOut() {
        party.raw = 12;
        party.campfire = Optional.of(FIRE);

        Cook cook = assertInstanceOf(Cook.class, FoodLine.INSTANCE.post(FOOD, party, 0.4));

        assertEquals(FIRE, cook.at());
        assertEquals(12, cook.count());
        assertTrue(FoodLine.INSTANCE.isWork(cook, FOOD, party));
        assertFalse(FoodLine.INSTANCE.isWork(new Cook(new Pos(40, 64, 0), 12, 0.4), FOOD, party),
                "a campfire not HOME's is not the line's");
    }

    @Test
    void withNoCampfireOneIsSetUpFromHomesCharcoal() {
        party.raw = 12;
        party.charcoal = 3;

        SetUp setUp = assertInstanceOf(SetUp.class, FoodLine.INSTANCE.post(FOOD, party, 0.4));

        assertEquals(java.util.List.of(SetUp.CAMPFIRE), setUp.stations());
        assertTrue(FoodLine.INSTANCE.isWork(setUp, FOOD, party));
    }

    @Test
    void withoutCharcoalThereIsNoCookingYet() {
        party.raw = 12;

        assertInstanceOf(Gather.class, FoodLine.INSTANCE.post(FOOD, party, 0.4),
                "a campfire is made with charcoal; without it the line gathers as it did");
    }

    @Test
    void nothingRawAtHomeGathers() {
        party.campfire = Optional.of(FIRE);

        assertInstanceOf(Gather.class, FoodLine.INSTANCE.post(FOOD, party, 0.4));
    }

    @Test
    void aCountBelowOneIsRefused() {
        assertTrue(FoodLine.INSTANCE.check(new Direction(new DirectionId("test:wood", "food"), 0, null))
                .isPresent());
    }
}
