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
import dev.luizloyola.autarkia.core.board.SiteBuilding;
import dev.luizloyola.autarkia.core.bp.Footprint;
import dev.luizloyola.autarkia.core.bp.Placement;
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
 * {@code shelter} (Luiz, 2026-10-20): right after HOME, before the base, it posts the dirt hut; it
 * waits while the hut goes up, and is met by any building standing with a door or a shelter a
 * member remembers near HOME.
 */
class ShelterLineTest {

    private static final Direction DIRECTION = new Direction(new DirectionId("autarkia:wood", "shelter"), 0, null);

    /** A party with a HOME and nothing else unless a test gives it. */
    private static final class Party implements PartyView {
        boolean home = true;
        boolean stands;
        boolean known;
        final List<Structure> structures = new ArrayList<>();

        @Override
        public PartyId party() {
            return PartyId.of(new UUID(1, 2));
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
        public boolean shelterStands() {
            return stands;
        }

        @Override
        public boolean shelterNearHome() {
            return known;
        }

        @Override
        public int members() {
            return 1;
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
            return false;
        }

        @Override
        public OptionalInt freeSlotsAtHome() {
            return OptionalInt.of(0);
        }
    }

    private static Structure hut(Structure.Phase phase) {
        return new Structure(UUID.randomUUID(), ShelterLine.BLUEPRINT, 1, Map.of(), Map.of(), new Pos(2, 64, 1),
                Placement.AS_DRAWN, new Footprint(0, 0, 3, 2), new Footprint(0, -2, 3, 2), phase, 0L, "");
    }

    private static Status judge(Party party) {
        return ShelterLine.INSTANCE.judge(DIRECTION, party);
    }

    @Test
    void itWaitsForHomeAndNothingElse() {
        Party party = new Party();
        party.home = false;
        assertEquals(Status.NO_HOME, judge(party));
        party.home = true;
        assertEquals(Status.Reading.UNMET, judge(party).reading(), "no base yet, and the hut is wanted anyway");
    }

    @Test
    void withNoShelterItPostsTheHut() {
        Party party = new Party();
        assertEquals("autarkia.direction.shelter.none", judge(party).langKey());
        SiteBuilding ask = assertInstanceOf(SiteBuilding.class, ShelterLine.INSTANCE.post(DIRECTION, party, 0.55));
        assertEquals("autarkia:dirt_hut", ask.blueprint());
        assertTrue(ShelterLine.INSTANCE.isWork(ask, DIRECTION, party), "so it is never posted twice");
        assertFalse(ShelterLine.INSTANCE.isWork(new SiteBuilding(HouseLine.BLUEPRINT, HouseLine.VARIANTS, 0.5),
                DIRECTION, party), "the house is the house line's");
        assertFalse(ShelterLine.INSTANCE.isWork(new dev.luizloyola.autarkia.core.board.Build(new UUID(3, 3),
                ShelterLine.BLUEPRINT, List.of(), 0.55), DIRECTION, party),
                "the hut's build is its building's: met another way, the line leaves it standing half built");
    }

    @Test
    void aHutGoingUpIsWaitedOnAndARefusedOneIsNoShelter() {
        for (Structure.Phase phase : List.of(Structure.Phase.SITED, Structure.Phase.CLEARING,
                Structure.Phase.LEVELLING, Structure.Phase.BUILDING)) {
            Party party = new Party();
            party.structures.add(hut(phase));
            assertEquals(Status.Reading.WAITING, judge(party).reading(), phase.name());
        }
        Party party = new Party();
        party.structures.add(hut(Structure.Phase.REFUSED));
        assertEquals(Status.Reading.UNMET, judge(party).reading());
    }

    @Test
    void aBuildingStandingWithADoorOrAShelterKnownNearHomeMeetsIt() {
        Party party = new Party();
        party.stands = true;
        assertEquals("autarkia.direction.shelter.stands", judge(party).langKey());
        assertEquals(Status.Reading.MET, judge(party).reading());
        party.stands = false;
        party.known = true;
        assertEquals("autarkia.direction.shelter.known", judge(party).langKey());
        assertEquals(Status.Reading.MET, judge(party).reading());
    }

    @Test
    void aHutGoingUpIsNoBuildingTheBaseWaitsFor() {
        Party party = new Party();
        party.structures.add(hut(Structure.Phase.BUILDING));
        assertFalse(party.building(), "it never stands over the base");
    }

    @Test
    void theHutIsAQuickBuilding() {
        assertTrue(Structure.QUICK.contains(ShelterLine.BLUEPRINT));
    }
}
