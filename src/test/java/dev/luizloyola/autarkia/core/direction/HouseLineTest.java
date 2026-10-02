package dev.luizloyola.autarkia.core.direction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.core.social.PartyId;
import dev.luizloyola.anima.core.territory.ChunkKey;
import dev.luizloyola.autarkia.core.board.GrowBuilding;
import dev.luizloyola.autarkia.core.board.SetUp;
import dev.luizloyola.autarkia.core.board.SiteBuilding;
import dev.luizloyola.autarkia.core.bp.Footprint;
import dev.luizloyola.autarkia.core.bp.Placement;
import dev.luizloyola.autarkia.core.builder.Growth;
import dev.luizloyola.autarkia.core.builder.Structure;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@code house}: it waits for HOME and its base, posts the choice of a site for the basic house,
 * waits while the house it sited is cleared, levelled and built, and asks again when a site was
 * refused.
 */
class HouseLineTest {

    private static final Direction DIRECTION = new Direction(new DirectionId("autarkia:wood", "house"), 0, null);

    /** A party with a HOME, its base, and whatever buildings a test gives it. */
    private static final class Party implements PartyView {
        boolean home = true;
        boolean base = true;
        boolean furnace;
        boolean furnaceHoused;
        Optional<Growth> growth = Optional.empty();
        final List<Structure> structures = new ArrayList<>();

        @Override
        public Optional<Growth> growth(SetUp.Station station) {
            return station == SetUp.FURNACE ? growth : Optional.empty();
        }

        @Override
        public boolean housed(PoiKind kind) {
            return !kind.equals(SetUp.FURNACE.kind()) || furnaceHoused;
        }

        @Override
        public PartyId party() {
            return PartyId.of(new UUID(1, 1));
        }

        @Override
        public Optional<Home> home() {
            return home ? Optional.of(Home.fresh()) : Optional.empty();
        }

        @Override
        public SortedSet<ChunkKey> area() {
            return new TreeSet<>(List.of(new ChunkKey(ChunkKey.OVERWORLD, 0, 0)));
        }

        @Override
        public List<Structure> structures() {
            return structures;
        }

        @Override
        public int members() {
            return 2;
        }

        @Override
        public OptionalInt storedAtHome(ItemSpec spec) {
            return OptionalInt.of(0);
        }

        @Override
        public OptionalInt foodAtHome() {
            return OptionalInt.of(0);
        }

        @Override
        public boolean hasAtHome(PoiKind kind) {
            return base && (kind.equals(SetUp.WORKBENCH.kind()) || kind.equals(SetUp.STORE.kind()))
                    || furnace && kind.equals(SetUp.FURNACE.kind());
        }

        @Override
        public OptionalInt freeSlotsAtHome() {
            return OptionalInt.of(27);
        }
    }

    private static Structure house(Structure.Phase phase) {
        Footprint pad = new Footprint(0, 0, 11, 12);
        return new Structure(UUID.randomUUID(), HouseLine.BLUEPRINT, 3, HouseLine.VARIANTS, Map.of(),
                new Pos(5, 64, 6), Placement.AS_DRAWN, new Footprint(1, 1, 9, 10), pad, phase, 0L, "");
    }

    private static Status.Reading reading(Party party) {
        return HouseLine.INSTANCE.judge(DIRECTION, party).reading();
    }

    @Test
    void itWaitsForHomeAndTheBase() {
        Party party = new Party();
        party.home = false;
        assertEquals(Status.NO_HOME, HouseLine.INSTANCE.judge(DIRECTION, party));
        party.home = true;
        party.base = false;
        assertEquals(Status.NO_BASE, HouseLine.INSTANCE.judge(DIRECTION, party));
    }

    @Test
    void withNoHouseItPostsTheChoiceOfASiteForTheBasicHouse() {
        Party party = new Party();

        assertEquals(Status.Reading.UNMET, reading(party));
        SiteBuilding ask = assertInstanceOf(SiteBuilding.class, HouseLine.INSTANCE.post(DIRECTION, party, 0.5));
        assertEquals("autarkia:basic_wooden_house", ask.blueprint());
        assertEquals("lv1", ask.variants().get("base"), "the crafting table and the chest (decision 6)");
        assertEquals("none", ask.variants().get("beds"), "beds wait for wool");
        assertTrue(HouseLine.INSTANCE.isWork(ask, DIRECTION, party), "so it is never posted twice");
        assertTrue(ask.open().isEmpty(), "no body does the choosing");
    }

    @Test
    void aSitedHouseIsWaitedOnAtEveryPhase() {
        for (Structure.Phase phase : List.of(Structure.Phase.SITED, Structure.Phase.LEVELLING,
                Structure.Phase.LEVELLED)) {
            Party party = new Party();
            party.structures.add(house(phase));

            assertEquals(Status.Reading.WAITING, reading(party), phase + " posts nothing more");
        }
    }

    @Test
    void aRefusedSiteIsNoHouse() {
        Party party = new Party();
        party.structures.add(house(Structure.Phase.REFUSED));

        assertEquals(Status.Reading.UNMET, reading(party), "the next choice keeps off the refused pad");
    }

    @Test
    void anotherBlueprintIsNotThisLinesHouse() {
        Party party = new Party();
        Structure shed = house(Structure.Phase.SITED);
        party.structures.add(new Structure(shed.id(), "autarkia:shed", 1, Map.of(), Map.of(), shed.anchor(),
                shed.placement(), shed.built(), shed.pad(), shed.phase(), 0L, ""));

        assertEquals(Status.Reading.UNMET, reading(party));
        assertFalse(HouseLine.INSTANCE.isWork(new SiteBuilding("autarkia:shed", Map.of(), 0.5), DIRECTION, party));
    }

    @Test
    void aStandingHouseHoldingEveryStationIsMet() {
        Party party = new Party();
        party.structures.add(house(Structure.Phase.BUILT));
        party.furnace = true;
        party.furnaceHoused = true;
        party.growth = Optional.of(new Growth(party.structures.get(0).id(), Map.of("base", "lv3")));

        assertEquals(Status.Reading.MET, reading(party));
    }

    @Test
    void aFurnaceKeptOutsideWantsTheHouseGrownToHoldOne() {
        Party party = new Party();
        Structure built = house(Structure.Phase.BUILT);
        party.structures.add(built);
        party.furnace = true;
        Map<String, String> lv3 = Map.of("base", "lv3", "beds", "none", "attic", "none");
        party.growth = Optional.of(new Growth(built.id(), lv3));

        Status status = HouseLine.INSTANCE.judge(DIRECTION, party);
        assertEquals(Status.Reading.UNMET, status.reading());
        assertEquals("autarkia.direction.house.grow.furnace", status.langKey());
        GrowBuilding ask = assertInstanceOf(GrowBuilding.class, HouseLine.INSTANCE.post(DIRECTION, party, 0.5));
        assertEquals(built.id(), ask.structure());
        assertEquals(lv3, ask.variants());
        assertEquals(SetUp.FURNACE.itemId(), ask.station());
        assertTrue(HouseLine.INSTANCE.isWork(ask, DIRECTION, party), "so it is never posted twice");
        assertFalse(HouseLine.INSTANCE.isWork(new GrowBuilding(built.id(), lv3, SetUp.STORE.itemId(), 0.5),
                DIRECTION, party), "a growth for room is the storage line's");
    }

    @Test
    void aFurnaceOutsideAHouseThatCannotGrowIsLeftBe() {
        Party party = new Party();
        party.structures.add(house(Structure.Phase.BUILT));
        party.furnace = true;

        assertEquals(Status.Reading.MET, reading(party));
    }

    @Test
    void aGrowingHouseIsWaitedOn() {
        Party party = new Party();
        party.structures.add(house(Structure.Phase.BUILT).growing(Map.of("base", "lv3"), ""));
        party.furnace = true;

        assertEquals(Status.Reading.WAITING, reading(party));
        assertTrue(party.growing());
    }

    @Test
    void aStandingHouseIsJudgedBeforeOneSitedAfterIt() {
        Party party = new Party();
        party.structures.add(house(Structure.Phase.SITED));
        Structure built = house(Structure.Phase.BUILT);
        party.structures.add(built);
        party.furnace = true;
        party.growth = Optional.of(new Growth(built.id(), Map.of("base", "lv3")));

        assertEquals("autarkia.direction.house.grow.furnace", HouseLine.INSTANCE.judge(DIRECTION, party).langKey());
    }
}
