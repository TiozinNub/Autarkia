package dev.luizloyola.autarkia.core.direction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.brain.knowledge.Region;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.ReadyFood;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.core.social.PartyId;
import dev.luizloyola.autarkia.core.board.CarrySplit;
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

    private static final Pos YARD = new Pos(10, 64, 10);
    private static final Direction FOOD = new Direction(new DirectionId("test:wood", "food"), 32, null);

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
            return Optional.of(new Home(Region.of(YARD), YARD, false));
        }

        @Override
        public int members() {
            return members;
        }

        @Override
        public OptionalInt storedAtHome(ItemSpec spec) {
            return OptionalInt.of(items);
        }

        @Override
        public OptionalInt readyFoodAtHome() {
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

        assertEquals(ReadyFood.SPEC, gather.spec());
        assertEquals(YARD, gather.yard());
        assertEquals(10 + 28, gather.target(),
                "56 points short at two a berry is 28 more items on top of the 10 there");
    }

    @Test
    void itKnowsItsOwnGatherAndNoOther() {
        Gather food = assertInstanceOf(Gather.class, FoodLine.INSTANCE.post(FOOD, party, 0.4));
        Gather logs = new Gather(Stock.LOGS, 64, YARD, 0.4, party.id, CarrySplit.INSTANCE);

        assertTrue(FoodLine.INSTANCE.isWork(food, FOOD, party));
        assertFalse(FoodLine.INSTANCE.isWork(logs, FOOD, party));
    }

    @Test
    void aCountBelowOneIsRefused() {
        assertTrue(FoodLine.INSTANCE.check(new Direction(new DirectionId("test:wood", "food"), 0, null))
                .isPresent());
    }
}
