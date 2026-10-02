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

    /** Where the party's next station goes; HOME is its chunk. */
    private static final Pos SPOT = new Pos(10, 64, 10);
    private static final Pos FURNACE = new Pos(12, 64, 10);
    private static final Direction CHARCOAL = new Direction(new DirectionId("test:wood", "charcoal"), 16, null);

    private static class Party implements PartyView {
        final PartyId id = PartyId.random();
        int members = 2;
        int charcoal = 0;
        boolean furnace = true;
        boolean running = false;
        final List<dev.luizloyola.autarkia.core.builder.Structure> structures = new java.util.ArrayList<>();

        @Override
        public List<dev.luizloyola.autarkia.core.builder.Structure> structures() {
            return structures;
        }

        @Override
        public PartyId party() {
            return id;
        }

        @Override
        public Optional<Home> home() {
            return Optional.of(Home.fresh());
        }

        @Override
        public java.util.SortedSet<dev.luizloyola.anima.core.territory.ChunkKey> area() {
            return new java.util.TreeSet<>(java.util.Set.of(dev.luizloyola.anima.core.territory.ChunkKey.at(
                    dev.luizloyola.anima.core.territory.ChunkKey.OVERWORLD, SPOT.x(), SPOT.z())));
        }

        @Override
        public Optional<Pos> spot() {
            return Optional.of(SPOT);
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
        assertEquals(SPOT, setUp.near());
        assertTrue(CharcoalLine.INSTANCE.isWork(setUp, CHARCOAL, party), "its own, so it is not posted twice");
        assertEquals(Direction.BUILDING, assertInstanceOf(SetUp.class,
                CharcoalLine.INSTANCE.post(CHARCOAL, party, Direction.UPKEEP)).priority(), 1e-9,
                "a station going down bids above the upkeep its Direction would");
        assertEquals(0.6, assertInstanceOf(SetUp.class, CharcoalLine.INSTANCE.post(CHARCOAL, party, 0.6))
                .priority(), 1e-9, "and never below what the table asks");
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
        Tend tend = new Tend(FURNACE, "minecraft:charcoal", Stock.PLANKS, true, AgentId.random(), 0L);
        assertTrue(CharcoalLine.INSTANCE.isWork(tend, CHARCOAL, party));
        Tend elsewhere = new Tend(new Pos(90, 64, 90), "minecraft:charcoal", Stock.PLANKS, true, AgentId.random(), 0L);
        assertFalse(CharcoalLine.INSTANCE.isWork(elsewhere, CHARCOAL, party));
    }

    private static dev.luizloyola.autarkia.core.builder.Structure houseGoingUp(
            dev.luizloyola.autarkia.core.bp.Footprint pad) {
        return new dev.luizloyola.autarkia.core.builder.Structure(new java.util.UUID(3, 3),
                "autarkia:basic_wooden_house", 3, java.util.Map.of(), java.util.Map.of(),
                new Pos(pad.minX(), 64, pad.minZ()), dev.luizloyola.autarkia.core.bp.Placement.AS_DRAWN, pad, pad,
                dev.luizloyola.autarkia.core.builder.Structure.Phase.BUILDING, 0L, "");
    }

    private List<Evolution.Posted> beats(dev.luizloyola.autarkia.core.board.PartyBoard board, int times) {
        Lines.register(CharcoalLine.INSTANCE);
        try {
            Node wood = new Node("test:wood", NodeKind.CORE, List.of(), true, Requirements.NONE,
                    List.of(CHARCOAL), java.util.Set.of("minecraft:charcoal"), java.util.Set.of());
            Tree tree = Tree.build(List.of(wood), java.util.Set.of("minecraft:charcoal"), key -> false).tree();
            PartyProgress progress = new PartyProgress();
            List<Evolution.Posted> posted = new java.util.ArrayList<>();
            for (int i = 0; i < times; i++) {
                posted.addAll(Evolution.beat(tree, progress, party, board).posted());
            }
            return posted;
        } finally {
            Lines.clear();
        }
    }

    /**
     * The house's torches wait on charcoal, and charcoal on this furnace: held for the length of
     * the build, the house never stood (forest, 2026-10-02).
     */
    @Test
    void aHouseGoingUpElsewhereStillGetsItsFurnaceOnce() {
        party.furnace = false;
        party.structures.add(houseGoingUp(new dev.luizloyola.autarkia.core.bp.Footprint(40, 40, 51, 52)));
        var board = new dev.luizloyola.autarkia.core.board.PartyBoard(party.id);

        List<Evolution.Posted> posted = beats(board, 3);

        assertEquals(1, posted.size(), "posted once, not once a beat");
        assertInstanceOf(SetUp.class, posted.get(0).project());
    }

    @Test
    void aFurnaceThatWouldGoDownOnTheSiteWaitsForTheBuilding() {
        party.furnace = false;
        party.structures.add(houseGoingUp(new dev.luizloyola.autarkia.core.bp.Footprint(0, 0, 11, 12)));

        assertTrue(beats(new dev.luizloyola.autarkia.core.board.PartyBoard(party.id), 3).isEmpty(),
                "the build would break it, and withdraws it every beat");
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
