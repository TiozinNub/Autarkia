package dev.luizloyola.autarkia.core.brain.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.autarkia.core.brain.sense.Being;
import dev.luizloyola.autarkia.core.brain.sense.BeingId;
import dev.luizloyola.autarkia.core.brain.sense.Pos;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The herd-memory rules end to end: 3+ head is one HERD memory, 1–2 are individual memories, a
 * wandered herd re-acquires by EXPAND-RECENTER over its inflated remembered area (auto-merging
 * returned outliers), a forming herd consumes loner memories on its ground, standing over a
 * visibly empty pasture downgrades the herd to whatever loners remain, and species never cross.
 */
class HerdNoterTest {

    private final PersonKnowledge knowledge = new PersonKnowledge();
    private final Pos observer = new Pos(0, 64, 0);

    private static Being herd(String species, Pos centroid, int count, int spread) {
        List<BeingId> members = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            members.add(BeingId.of(UUID.randomUUID()));
        }
        return herd(species, centroid, count, spread, members);
    }

    /** A herd whose member ids are PINNED — identity consumption needs to name them. */
    private static Being herd(String species, Pos centroid, int count, int spread,
                              List<BeingId> members) {
        return new Being(BeingId.of(UUID.randomUUID()), Being.Kind.PASSIVE, species, "", null,
                centroid, 8.0, count, spread, true, members, Being.Activity.IDLE,
                Being.Locomotion.STILL, false, false, false, false, false, Being.Gear.NONE,
                Being.Identified.INDIVIDUAL, Being.Awareness.SEEN);
    }

    private static Being loner(String species, Pos at) {
        return loner(species, at, BeingId.of(UUID.randomUUID()));
    }

    /** A loner with a PINNED identity — the same animal, re-sighted wherever. */
    private static Being loner(String species, Pos at, BeingId who) {
        return new Being(who, Being.Kind.PASSIVE, species, "", null, at, 8.0, 1, 0, true,
                List.of(), Being.Activity.IDLE, Being.Locomotion.STILL, false, false, false,
                false, false, Being.Gear.NONE, Being.Identified.INDIVIDUAL,
                Being.Awareness.SEEN);
    }

    private List<PoiMemory> herds() {
        return List.copyOf(knowledge.all(PoiKind.HERD));
    }

    @Test
    void threeHeadBecomeOneHerdMemoryAndResightingsRefreshSilently() {
        List<SenseEvent> first = HerdNoter.note(observer,
                List.of(herd("cow", new Pos(10, 64, 10), 5, 4)), knowledge, 100);
        assertEquals(1, first.size(), "a new herd is a noticed event");
        assertEquals(SenseEvent.Type.NOTED, first.get(0).type());
        assertEquals(1, herds().size());
        assertEquals(5, herds().get(0).units());
        assertEquals("cow", herds().get(0).detail());

        List<SenseEvent> again = HerdNoter.note(observer,
                List.of(herd("cow", new Pos(11, 64, 10), 5, 4)), knowledge, 200);
        assertTrue(again.isEmpty(), "a watched pasture must not journal every beat");
        assertEquals(1, herds().size(), "…and the memory recentered, never duplicated");
        assertEquals(200, herds().get(0).lastSeenTick());
    }

    @Test
    void aWanderedHerdRecentersInsideTheInflatedAreaAndAFarOneIsASecondMemory() {
        HerdNoter.note(observer, List.of(herd("cow", new Pos(10, 64, 10), 5, 4)), knowledge, 100);

        // Drifted ~10 blocks: outside the remembered bounds (±4) but inside them inflated 2.5x
        // (±10).
        List<SenseEvent> drifted = HerdNoter.note(observer,
                List.of(herd("cow", new Pos(19, 64, 10), 5, 4)), knowledge, 200);
        assertTrue(drifted.isEmpty(), "a re-found herd is the same herd");
        assertEquals(1, herds().size());
        assertEquals(new Pos(19, 64, 10), herds().get(0).anchor(), "recentered where it now is");

        // A same-species herd 60 blocks out is genuinely another herd.
        List<SenseEvent> far = HerdNoter.note(observer,
                List.of(herd("cow", new Pos(70, 64, 10), 4, 4)), knowledge, 300);
        assertEquals(1, far.size());
        assertEquals(2, herds().size());
    }

    @Test
    void oneOrTwoHeadRememberAsEach() {
        BeingId first = BeingId.of(UUID.randomUUID());
        List<SenseEvent> events = HerdNoter.note(observer,
                List.of(loner("cow", new Pos(10, 64, 10), first),
                        loner("cow", new Pos(40, 64, 10))),
                knowledge, 100);
        assertEquals(2, events.size(), "two lone cows in different places — remember as each");
        assertEquals(2, herds().size());
        assertTrue(herds().stream().allMatch(m -> m.units() == 1));

        // The same cow shuffled a couple of blocks re-anchors silently.
        List<SenseEvent> shuffled = HerdNoter.note(observer,
                List.of(loner("cow", new Pos(12, 64, 10), first)), knowledge, 200);
        assertTrue(shuffled.isEmpty());
        assertEquals(2, herds().size());
    }

    @Test
    void aWanderingPigDragsItsOneMemoryAlong() {
        // A strolling pig used to shed a trail of ghost memories — proximity matching cannot
        // follow a walk longer than its radius, and identity can.
        BeingId pig = BeingId.of(UUID.randomUUID());
        HerdNoter.note(observer, List.of(loner("pig", new Pos(10, 64, 10), pig)), knowledge, 100);
        HerdNoter.note(observer, List.of(loner("pig", new Pos(22, 64, 15), pig)), knowledge, 200);
        List<SenseEvent> third = HerdNoter.note(observer,
                List.of(loner("pig", new Pos(45, 64, 40), pig)), knowledge, 300);

        assertTrue(third.isEmpty(), "the same pig re-found is never news");
        assertEquals(1, herds().size(), "ONE pig, ONE memory — however far it strolled");
        assertEquals(new Pos(45, 64, 40), herds().get(0).anchor());
        assertEquals(pig.value(), herds().get(0).individual());
    }

    @Test
    void twoPigsCrowdedTogetherStayTwoMemories() {
        // The inverse guarantee: identity separates what proximity would fuse.
        List<SenseEvent> events = HerdNoter.note(observer,
                List.of(loner("pig", new Pos(10, 64, 10)), loner("pig", new Pos(11, 64, 10))),
                knowledge, 100);
        assertEquals(2, events.size());
        assertEquals(2, herds().size(), "shoulder to shoulder, still two pigs");
    }

    @Test
    void aLonerJoiningAHerdRetiresItsOwnMemory() {
        BeingId stray = BeingId.of(UUID.randomUUID());
        HerdNoter.note(observer, List.of(loner("cow", new Pos(40, 64, 40), stray)), knowledge, 100);
        HerdNoter.note(observer, List.of(herd("cow", new Pos(30, 64, 30), 4, 4)), knowledge, 200);
        assertEquals(2, herds().size(), "a far stray and a herd coexist");

        // The stray wanders onto the herd's ground: the herd speaks for it now, and its own
        // memory does not linger as a ghost where it used to graze.
        HerdNoter.note(observer, List.of(loner("cow", new Pos(31, 64, 31), stray)), knowledge, 300);
        assertEquals(1, herds().size(), "only the herd remains");
        assertEquals(4, herds().get(0).units());
    }

    @Test
    void aFormingHerdConsumesItsMembersLonerMemories() {
        BeingId a = BeingId.of(UUID.randomUUID());
        BeingId b = BeingId.of(UUID.randomUUID());
        HerdNoter.note(observer, List.of(loner("cow", new Pos(10, 64, 10), a),
                loner("cow", new Pos(14, 64, 10), b)), knowledge, 100);
        assertEquals(2, herds().size());

        // A third cow arrives; the herd's member list NAMES a and b — their loner memories
        // retire with the collapse, by identity rather than by ground.
        HerdNoter.note(observer, List.of(herd("cow", new Pos(12, 64, 10), 3, 4,
                List.of(a, b, BeingId.of(UUID.randomUUID())))), knowledge, 200);
        assertEquals(1, herds().size(), "the herd speaks for its members");
        assertEquals(3, herds().get(0).units());
    }

    @Test
    void aLonerGlimpsedInAFarHerdAreaIsTheHerd() {
        // The remembered herd is beyond absence range — a cow glimpsed at distance inside its
        // (inflated) area is presumed a member, not a new loner to file.
        HerdNoter.note(observer, List.of(herd("cow", new Pos(30, 64, 30), 5, 4)), knowledge, 100);
        List<SenseEvent> events = HerdNoter.note(observer,
                List.of(loner("cow", new Pos(32, 64, 31))), knowledge, 200);
        assertTrue(events.isEmpty());
        assertEquals(1, herds().size(), "no shadow loner memory under the herd's own area");
        assertEquals(5, herds().get(0).units(), "and the herd memory stands as remembered");
    }

    @Test
    void anUnseenPastureIsNotForgottenJustForStandingNearIt() {
        // Close to the remembered anchor but perceiving NOTHING of the species: zero sightings
        // are not witnessed absence — staleness owns true disappearance.
        HerdNoter.note(observer, List.of(herd("sheep", new Pos(6, 64, 6), 4, 4)), knowledge, 100);
        List<SenseEvent> events = HerdNoter.note(new Pos(6, 64, 6), List.of(), knowledge, 500);
        assertTrue(events.isEmpty());
        assertEquals(1, herds().size(), "absence of evidence just waits");
    }

    @Test
    void standingOverAnEmptyPastureDowngradesToTheLonersActuallyThere() {
        HerdNoter.note(observer, List.of(herd("cow", new Pos(6, 64, 6), 5, 4)), knowledge, 100);

        // They walk to the remembered spot; one cow grazes there, the herd is gone.
        List<SenseEvent> events = HerdNoter.note(new Pos(6, 64, 6),
                List.of(loner("cow", new Pos(8, 64, 6))), knowledge, 500);
        assertEquals(2, events.size(), "one forgot (the herd), one noticed (the loner): " + events);
        assertEquals(1, herds().size());
        assertEquals(1, herds().get(0).units(), "what remains is the cow actually seen");
    }

    @Test
    void aHerdStandingWhereRememberedIsNotDowngraded() {
        HerdNoter.note(observer, List.of(herd("cow", new Pos(6, 64, 6), 5, 4)), knowledge, 100);
        List<SenseEvent> events = HerdNoter.note(new Pos(6, 64, 6),
                List.of(herd("cow", new Pos(7, 64, 6), 5, 4)), knowledge, 500);
        assertTrue(events.isEmpty(), "present and accounted for — no forget, no re-notice");
        assertEquals(1, herds().size());
        assertEquals(5, herds().get(0).units());
    }

    @Test
    void cowAndSheepFlocksOnTheSameGroundStayTwoMemories() {
        HerdNoter.note(observer, List.of(
                herd("cow", new Pos(10, 64, 10), 4, 4),
                herd("sheep", new Pos(12, 64, 11), 3, 4)), knowledge, 100);
        assertEquals(2, herds().size(), "merging never crosses species");

        HerdNoter.note(observer, List.of(herd("cow", new Pos(12, 64, 10), 4, 4)), knowledge, 200);
        assertEquals(2, herds().size(), "a cow re-sighting recenters the COW memory only");
        assertTrue(herds().stream().anyMatch(m -> m.detail().equals("sheep") && m.units() == 3));
    }

    @Test
    void standingOverAGonePigsSpotForgetsIt() {
        // The clearing rule: walked up to the remembered spot with the pig absent from
        // perception entirely — witnessed absence, so the memory goes.
        BeingId pig = BeingId.of(UUID.randomUUID());
        HerdNoter.note(observer, List.of(loner("pig", new Pos(6, 64, 6), pig)), knowledge, 100);
        List<SenseEvent> events = HerdNoter.note(new Pos(6, 64, 6), List.of(), knowledge, 500);

        assertEquals(1, events.size());
        assertEquals(SenseEvent.Type.FORGOT, events.get(0).type());
        assertEquals(0, knowledge.size(), "the pig is provably not here — nothing to remember");
    }

    @Test
    void aMerelyRememberedPigIsNotForgotten() {
        // Object permanence is not absence: the pig slipped out of sight moments ago (the
        // pillar case) — its track is REMEMBERED, so standing nearby clears nothing.
        BeingId pig = BeingId.of(UUID.randomUUID());
        HerdNoter.note(observer, List.of(loner("pig", new Pos(6, 64, 6), pig)), knowledge, 100);
        Being remembered = new Being(pig, Being.Kind.PASSIVE, "pig", "", null,
                new Pos(7, 64, 6), 4.0, 1, 0, true, List.of(), Being.Activity.IDLE,
                Being.Locomotion.STILL, false, false, false, false, false, Being.Gear.NONE,
                Being.Identified.INDIVIDUAL, Being.Awareness.REMEMBERED);
        List<SenseEvent> events = HerdNoter.note(new Pos(6, 64, 6), List.of(remembered),
                knowledge, 500);

        assertTrue(events.isEmpty());
        assertEquals(1, knowledge.size(), "still perceived, just not seen right now");
    }

    @Test
    void aDifferentPigGrazingThereSavesNothing() {
        // Identity makes absence exact: some OTHER pig standing on the spot is not the
        // remembered pig — the memory still clears, and the newcomer notes as itself.
        BeingId gone = BeingId.of(UUID.randomUUID());
        HerdNoter.note(observer, List.of(loner("pig", new Pos(6, 64, 6), gone)), knowledge, 100);
        List<SenseEvent> events = HerdNoter.note(new Pos(6, 64, 6),
                List.of(loner("pig", new Pos(6, 64, 7))), knowledge, 500);

        assertEquals(2, events.size(), "one forgot (the gone pig), one noticed (the newcomer)");
        assertEquals(1, knowledge.size());
        assertEquals(new Pos(6, 64, 7), herds().get(0).anchor());
    }

    @Test
    void rememberedSightingsNeverWrite() {
        Being remembered = new Being(BeingId.of(UUID.randomUUID()), Being.Kind.PASSIVE, "cow",
                "", null, new Pos(10, 64, 10), 8.0, 5, 4, true, List.of(), Being.Activity.IDLE,
                Being.Locomotion.STILL, false, false, false, false, false, Being.Gear.NONE,
                Being.Identified.INDIVIDUAL, Being.Awareness.REMEMBERED);
        List<SenseEvent> events = HerdNoter.note(observer, List.of(remembered), knowledge, 100);
        assertTrue(events.isEmpty());
        assertEquals(0, knowledge.size(), "memory of a memory is not a sighting");
    }
}
