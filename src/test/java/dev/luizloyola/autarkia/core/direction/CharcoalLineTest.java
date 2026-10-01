package dev.luizloyola.autarkia.core.direction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.craft.Furnace;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.core.social.PartyId;
import dev.luizloyola.autarkia.core.board.Fire;
import dev.luizloyola.autarkia.core.board.SetUp;
import dev.luizloyola.autarkia.core.board.Stock;
import dev.luizloyola.autarkia.core.board.Tend;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

/** {@code charcoal}: a furnace at HOME first, then a load for the shortfall, waiting while it smelts. */
class CharcoalLineTest {

    private static final Pos YARD = new Pos(10, 64, 10);
    private static final Pos FURNACE = new Pos(12, 64, 10);
    private static final Direction CHARCOAL = new Direction(new DirectionId("test:wood", "charcoal"), 16, null);

    private static class Party implements PartyView {
        final PartyId id = PartyId.random();
        int members = 2;
        int charcoal = 0;
        boolean furnace = true;
        boolean running = false;

        @Override
        public PartyId party() {
            return id;
        }

        @Override
        public Optional<Home> home() {
            return Optional.of(Home.at(YARD));
        }

        @Override
        public int members() {
            return members;
        }

        @Override
        public OptionalInt storedAtHome(ItemSpec spec) {
            return OptionalInt.of(charcoal);
        }

        @Override
        public OptionalInt foodAtHome() {
            return OptionalInt.of(0);
        }

        @Override
        public boolean hasAtHome(PoiKind kind) {
            return kind != Furnace.POI || furnace;
        }

        @Override
        public Optional<Pos> placeAtHome(PoiKind kind) {
            return kind == Furnace.POI && furnace ? Optional.of(FURNACE) : Optional.empty();
        }

        @Override
        public boolean runningAtHome(PoiKind kind) {
            return running;
        }

        @Override
        public OptionalInt freeSlotsAtHome() {
            return OptionalInt.of(27);
        }
    }

    private final Party party = new Party();

    @Test
    void sixteenAMember() {
        party.charcoal = 31;
        assertEquals(Status.Reading.UNMET, CharcoalLine.INSTANCE.judge(CHARCOAL, party).reading());
        party.charcoal = 32;
        assertEquals(Status.Reading.MET, CharcoalLine.INSTANCE.judge(CHARCOAL, party).reading());
    }

    @Test
    void withNoFurnaceAtHomeItPutsOneDown() {
        party.furnace = false;
        SetUp setUp = assertInstanceOf(SetUp.class, CharcoalLine.INSTANCE.post(CHARCOAL, party, 0.5));
        assertEquals(List.of(SetUp.FURNACE), setUp.stations());
        assertEquals(YARD, setUp.near());
        assertTrue(CharcoalLine.INSTANCE.isWork(setUp, CHARCOAL, party), "its own, so it is not posted twice");
    }

    @Test
    void thenItLoadsTheShortfall() {
        party.charcoal = 10;
        Fire fire = assertInstanceOf(Fire.class, CharcoalLine.INSTANCE.post(CHARCOAL, party, 0.5));
        assertEquals(FURNACE, fire.at());
        assertEquals(22, fire.count(), "32 wanted, 10 there");
        assertTrue(CharcoalLine.INSTANCE.isWork(fire, CHARCOAL, party));
    }

    @Test
    void aLoadIsNeverMoreThanAFurnaceStack() {
        party.members = 10;
        assertEquals(64, assertInstanceOf(Fire.class, CharcoalLine.INSTANCE.post(CHARCOAL, party, 0.5)).count());
    }

    @Test
    void whileTheFurnaceSmeltsItWaits() {
        party.running = true;
        assertEquals(Status.Reading.WAITING, CharcoalLine.INSTANCE.judge(CHARCOAL, party).reading(),
                "coming back for it is a Tend's, not another load");
    }

    @Test
    void comingBackToItsFurnaceIsItsWork() {
        Tend tend = new Tend(FURNACE, "minecraft:charcoal", Stock.PLANKS, YARD, AgentId.random(), 0L);
        assertTrue(CharcoalLine.INSTANCE.isWork(tend, CHARCOAL, party));
        Tend elsewhere = new Tend(new Pos(90, 64, 90), "minecraft:charcoal", Stock.PLANKS, YARD, AgentId.random(), 0L);
        assertFalse(CharcoalLine.INSTANCE.isWork(elsewhere, CHARCOAL, party));
    }

    @Test
    void itWaitsForTheBase() {
        PartyView noBase = new Party() {
            @Override
            public boolean hasAtHome(PoiKind kind) {
                return false;
            }
        };
        assertEquals(Status.NO_BASE, CharcoalLine.INSTANCE.judge(CHARCOAL, noBase));
    }
}
