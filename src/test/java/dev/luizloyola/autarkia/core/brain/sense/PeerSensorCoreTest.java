package dev.luizloyola.autarkia.core.brain.sense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.autarkia.core.person.PersonId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.jspecify.annotations.Nullable;

/**
 * The people sense end to end on scripted bodies. The cascade is cheap-first: the cone culls
 * before the rays, and sound short-circuits both.
 */
class PeerSensorCoreTest {

    /** She stands at origin facing +Z (yaw 0); the cone default is 200°, radius 24. */
    private final Pos self = new Pos(0, 64, 0);
    private final PeerSensorCore sensor = new PeerSensorCore();
    private final FakePeerWorld world = new FakePeerWorld();
    private long now;

    private List<PeerEvent> tickN(int ticks) {
        List<PeerEvent> events = new ArrayList<>();
        for (int i = 0; i < ticks; i++) {
            events.addAll(sensor.tick(self, 0.0, now++, world));
        }
        return events;
    }

    @Test
    void someoneAheadIsSpottedAsSeen() {
        world.add("Ahead", new Pos(0, 64, 5), 5.0, Peer.Activity.IDLE);
        List<PeerEvent> events = tickN(1);

        assertEquals(1, events.size());
        assertEquals(PeerEvent.Type.SPOTTED, events.get(0).type());
        assertEquals(Peer.Awareness.SEEN, events.get(0).peer().awareness());
        assertEquals("Ahead", sensor.peers().get(0).name());
    }

    @Test
    void behindHerBackSpotsNothingAndSpendsNoRays() {
        world.add("Lurker", new Pos(0, 64, -5), 5.0, Peer.Activity.IDLE);
        List<PeerEvent> events = tickN(30);

        assertTrue(events.isEmpty(), "outside the cone: unseen");
        assertEquals(0, world.rayChecks, "the cone culls BEFORE the rays — cheap-first cascade");
    }

    @Test
    void aWallBlocksTheSpot() {
        PersonId lurker = world.add("Walled", new Pos(0, 64, 5), 5.0, Peer.Activity.IDLE);
        world.hidden.add(lurker);
        List<PeerEvent> events = tickN(30);

        assertTrue(events.isEmpty(), "in cone but no clear ray to any body part: unseen");
        assertTrue(world.rayChecks > 0, "the cone passed, so the rays were genuinely consulted");
    }

    @Test
    void soundShortCircuitsConeAndWalls() {
        PersonId noisy = world.add("Noisy", new Pos(0, 64, -5), 5.0, Peer.Activity.MINING);
        world.hidden.add(noisy); // behind her and walled off — only the ears can find this one
        sensor.heard(world.bodies.get(noisy), now);
        List<PeerEvent> events = tickN(1);

        assertEquals(1, events.size());
        assertEquals(PeerEvent.Type.SPOTTED, events.get(0).type());
        assertEquals(Peer.Awareness.HEARD, events.get(0).peer().awareness());
    }

    @Test
    void thePillarWalkNeverFlickers() {
        PersonId walker = world.add("Walker", new Pos(0, 64, 5), 5.0, Peer.Activity.IDLE);
        List<PeerEvent> spotted = tickN(2);
        assertEquals(1, spotted.size());

        world.hidden.add(walker); // steps behind the pillar
        List<PeerEvent> during = tickN(40);
        assertTrue(during.isEmpty(), "no LOST mid-pillar — 'someone there' all the way");
        assertEquals(Peer.Awareness.REMEMBERED, sensor.peers().get(0).awareness());

        world.hidden.remove(walker); // steps out the other side
        List<PeerEvent> after = tickN(30);
        assertTrue(after.stream().noneMatch(e -> e.type() == PeerEvent.Type.SPOTTED),
                "she never thought they left, so no re-spot either");
        assertEquals(Peer.Awareness.SEEN, sensor.peers().get(0).awareness());
    }

    @Test
    void theLingerExpiresIntoLost() {
        PersonId ghost = world.add("Gone", new Pos(0, 64, 5), 5.0, Peer.Activity.IDLE);
        tickN(2);
        world.hidden.add(ghost);
        List<PeerEvent> events = tickN(PeerSensorCore.lingerTicks() + 60);

        assertEquals(1, events.stream().filter(e -> e.type() == PeerEvent.Type.LOST).count());
        assertTrue(sensor.peers().isEmpty(), "forgotten — the linger is a grace, not forever");
    }

    @Test
    void activityChangesEmitOnTheAttentionBeat() {
        PersonId busy = world.add("Busy", new Pos(0, 64, 3), 3.0, Peer.Activity.IDLE);
        tickN(2);
        world.set(busy, new Pos(0, 64, 3), 3.0, Peer.Activity.MINING);
        List<PeerEvent> events = tickN(10);

        assertEquals(1, events.stream()
                .filter(e -> e.type() == PeerEvent.Type.ACTIVITY_CHANGED).count());
        PeerEvent change = events.stream()
                .filter(e -> e.type() == PeerEvent.Type.ACTIVITY_CHANGED).findFirst().orElseThrow();
        assertEquals(Peer.Activity.IDLE, change.was());
        assertEquals(Peer.Activity.MINING, change.peer().activity());
    }

    @Test
    void rememberedReadingsStayFrozen() {
        PersonId walled = world.add("Frozen", new Pos(0, 64, 5), 5.0, Peer.Activity.MINING);
        tickN(2);
        world.hidden.add(walled);
        world.set(walled, new Pos(0, 64, 5), 5.0, Peer.Activity.IDLE); // world moves on, unseen
        List<PeerEvent> events = tickN(40);

        assertTrue(events.isEmpty(), "no activity events from behind the wall");
        assertEquals(Peer.Activity.MINING, sensor.peers().get(0).activity(),
                "the remembered reading is the LAST LIVE one, frozen");
    }

    @Test
    void earsDoNotReadTheUnseenBodysActivity() {
        // Behind her and walled: the ear is the only channel. The visual classifier says
        // AT_CRAFTING, the sound said moving, and sound is all she has — a heard-only peer once
        // read "at_crafting" through the back of her head.
        PersonId noisy = world.add("Rustler", new Pos(0, 64, -5), 5.0, Peer.Activity.AT_CRAFTING);
        world.hidden.add(noisy);
        PeerReading heard = new PeerReading(noisy, "Rustler", new Pos(0, 64, -5), 5.0,
                Peer.Locomotion.WALKING, false, false, Peer.Activity.IDLE);
        sensor.heard(heard, now);
        List<PeerEvent> events = tickN(PeerSensorCore.HEARD_FRESH_TICKS - 2);

        assertEquals(Peer.Activity.IDLE, sensor.peers().get(0).activity(),
                "the heard reading holds — no visual classification through walls");
        assertEquals(Peer.Locomotion.WALKING, sensor.peers().get(0).locomotion(),
                "the steps' story stands on the legs axis");
        assertTrue(events.stream().noneMatch(e -> e.type() == PeerEvent.Type.ACTIVITY_CHANGED),
                "and no phantom activity flips while the ear is the only channel");
    }

    @Test
    void earsCannotSeeAGazeOrACrouch() {
        // A sneaking someone behind her back, staring, making one loud noise: sound places them and
        // says what they did, never that they are watching or crouching — "watching her" was once
        // reported from behind her back.
        PersonId starer = world.add("Starer", new Pos(0, 64, -5), 5.0, Peer.Activity.MINING);
        world.hidden.add(starer);
        sensor.heard(new PeerReading(starer, "Starer", new Pos(0, 64, -5), 5.0,
                Peer.Locomotion.STILL, true, true, Peer.Activity.MINING), now);
        tickN(2);

        Peer heard = sensor.peers().get(0);
        assertTrue(!heard.watching(), "gaze is an eyes-only read");
        assertTrue(!heard.sneaking(), "posture is an eyes-only read");
        assertEquals(Peer.Activity.MINING, heard.activity(), "but the sound's story stands");
    }

    @Test
    void aHeardActivityDecaysWhenTheSoundStops() {
        PersonId knocker = world.add("Knocker", new Pos(0, 64, -5), 5.0, Peer.Activity.MINING);
        world.hidden.add(knocker);
        sensor.heard(new PeerReading(knocker, "Knocker", new Pos(0, 64, -5), 5.0,
                Peer.Locomotion.WALKING, false, false, Peer.Activity.MINING), now);
        List<PeerEvent> events = tickN(PeerSensorCore.heardActivityDecayTicks() + 20);

        assertEquals(Peer.Activity.IDLE, sensor.peers().get(0).activity(),
                "no more knocks: 'mining' faded to 'just someone there' — never stuck forever");
        assertEquals(Peer.Locomotion.STILL, sensor.peers().get(0).locomotion(),
                "the legs faded with it");
        assertTrue(events.stream().anyMatch(e -> e.type() == PeerEvent.Type.ACTIVITY_CHANGED
                        && e.was() == Peer.Activity.MINING),
                "and the fade is a narrated event, not a silent edit");
    }

    @Test
    void soundDoesNotSayWho() {
        PersonId walled = world.add("Masked", new Pos(0, 64, 5), 5.0, Peer.Activity.MINING);
        world.hidden.add(walled); // in front but walled: only the ear knows them
        sensor.heard(new PeerReading(walled, "Masked", new Pos(0, 64, 5), 5.0,
                Peer.Locomotion.STILL, false, false, Peer.Activity.MINING), now);
        tickN(3);
        Peer heardOnly = sensor.peers().get(0);
        assertEquals("someone", heardOnly.knownAs(), "ears carry no name");
        assertTrue(!heardOnly.identified());

        world.hidden.remove(walled); // the wall comes down — the eyes catch up
        List<PeerEvent> events = tickN(10);
        assertEquals(1, events.stream()
                .filter(e -> e.type() == PeerEvent.Type.RECOGNIZED).count());
        assertEquals("Masked", sensor.peers().get(0).knownAs(), "a face at last");
    }

    @Test
    void theAxesCoexist() {
        // The split's point (decision: Luiz): eating while walking keeps both facts; the arms
        // never eat the legs.
        PersonId snacker = world.add("Snacker", new Pos(0, 64, 3), 3.0, Peer.Activity.EATING);
        world.bodies.put(snacker, new PeerReading(snacker, "Snacker", new Pos(0, 64, 3), 3.0,
                Peer.Locomotion.WALKING, false, false, Peer.Activity.EATING));
        tickN(2);

        Peer peer = sensor.peers().get(0);
        assertEquals(Peer.Activity.EATING, peer.activity());
        assertEquals(Peer.Locomotion.WALKING, peer.locomotion());
        assertEquals("eating, walking", peer.tell());
    }

    @Test
    void attentionScalesWithDistance() {
        PersonId near = world.add("Near", new Pos(0, 64, 2), 2.0, Peer.Activity.IDLE);
        PersonId far = world.add("Far", new Pos(0, 64, 23), 23.0, Peer.Activity.IDLE);
        tickN(120);

        int nearReads = world.readCounts.getOrDefault(near, 0);
        int farReads = world.readCounts.getOrDefault(far, 0);
        assertTrue(nearReads > farReads * 3,
                "point-blank attention vs edge-of-range glances: " + nearReads + " vs " + farReads);
    }

    /** Scripted bodies: position/activity settable, walls per person, ray and read counters. */
    private static final class FakePeerWorld implements PeerWorld {
        final Map<PersonId, PeerReading> bodies = new LinkedHashMap<>();
        final Set<PersonId> hidden = new HashSet<>();
        final Map<PersonId, Integer> readCounts = new HashMap<>();
        int rayChecks;

        PersonId add(String name, Pos pos, double distance, Peer.Activity activity) {
            PersonId id = PersonId.random();
            bodies.put(id, new PeerReading(id, name, pos, distance, Peer.Locomotion.STILL, false, false, activity));
            return id;
        }

        void set(PersonId id, Pos pos, double distance, Peer.Activity activity) {
            bodies.put(id, new PeerReading(id, bodies.get(id).name(), pos, distance,
                    Peer.Locomotion.STILL, false, false, activity));
        }

        @Override
        public List<PeerReading> candidates() {
            return List.copyOf(bodies.values());
        }

        @Override
        public @Nullable PeerReading reading(PersonId id) {
            readCounts.merge(id, 1, Integer::sum);
            return bodies.get(id);
        }

        @Override
        public boolean inSight(PersonId id) {
            rayChecks++;
            return !hidden.contains(id);
        }
    }
}
