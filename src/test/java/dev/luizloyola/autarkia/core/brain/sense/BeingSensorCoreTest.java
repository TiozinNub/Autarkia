package dev.luizloyola.autarkia.core.brain.sense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.jspecify.annotations.Nullable;

/**
 * The being sense end to end on scripted bodies: the peer-sensor guarantees carried over, plus
 * the widened organ's own — the universal identification ladder (step → voice-named species →
 * sight), masking below each rung, the monster approach trend, herd collapse at three head, and
 * the backlog-scaled ray budget that defers but never skips.
 */
class BeingSensorCoreTest {

    /** The observer stands at origin facing +Z (yaw 0); radius 24, ray budget base 8. */
    private final Pos self = new Pos(0, 64, 0);
    private final BeingSensorCore sensor = new BeingSensorCore();
    private final FakeBeingWorld world = new FakeBeingWorld();
    private long now;

    private List<BeingEvent> tickN(int ticks) {
        List<BeingEvent> events = new ArrayList<>();
        for (int i = 0; i < ticks; i++) {
            world.newTick();
            events.addAll(sensor.tick(self, 0.0, 0.0, now++, world));
        }
        return events;
    }

    private Being only() {
        List<Being> beings = sensor.beings();
        assertEquals(1, beings.size(), "expected exactly one perceived being: " + beings);
        return beings.get(0);
    }

    // --- the carried-over person guarantees ------------------------------------------------------

    @Test
    void someoneAheadIsSpottedAsSeen() {
        world.addPerson("Ahead", new Pos(0, 64, 5), 5.0, Being.Activity.IDLE);
        List<BeingEvent> events = tickN(1);

        assertEquals(1, events.size());
        assertEquals(BeingEvent.Type.SPOTTED, events.get(0).type());
        assertEquals(Being.Awareness.SEEN, events.get(0).being().awareness());
        assertEquals("Ahead", only().knownAs(), "sight names the individual immediately");
        assertEquals(Being.Identified.INDIVIDUAL, only().identified());
    }

    @Test
    void behindTheirBackSpotsNothingAndSpendsNoRays() {
        world.addPerson("Lurker", new Pos(0, 64, -5), 5.0, Being.Activity.IDLE);
        List<BeingEvent> events = tickN(30);

        assertTrue(events.isEmpty(), "outside the cone: unseen");
        assertEquals(0, world.rayChecks, "the cone culls BEFORE the rays — cheap-first cascade");
    }

    @Test
    void aWallBlocksTheSpot() {
        BeingId lurker = world.addPerson("Walled", new Pos(0, 64, 5), 5.0, Being.Activity.IDLE);
        world.hidden.add(lurker);
        List<BeingEvent> events = tickN(30);

        assertTrue(events.isEmpty(), "in cone but no clear ray to any body part: unseen");
        assertTrue(world.rayChecks > 0, "the cone passed, so the rays were genuinely consulted");
    }

    @Test
    void soundShortCircuitsConeAndWalls() {
        BeingId noisy = world.addPerson("Noisy", new Pos(0, 64, -5), 5.0, Being.Activity.MINING);
        world.hidden.add(noisy); // behind them and walled off — only the ears can find this one
        sensor.heard(world.bodies.get(noisy), now, false);
        List<BeingEvent> events = tickN(1);

        assertEquals(1, events.size());
        assertEquals(BeingEvent.Type.SPOTTED, events.get(0).type());
        assertEquals(Being.Awareness.HEARD, events.get(0).being().awareness());
    }

    @Test
    void thePillarWalkNeverFlickers() {
        BeingId walker = world.addPerson("Walker", new Pos(0, 64, 5), 5.0, Being.Activity.IDLE);
        List<BeingEvent> spotted = tickN(2);
        assertEquals(1, spotted.size());

        world.hidden.add(walker); // steps behind the pillar
        List<BeingEvent> during = tickN(40);
        assertTrue(during.stream().noneMatch(e -> e.type() == BeingEvent.Type.LOST),
                "no LOST mid-pillar — 'someone there' all the way");
        assertEquals(1, during.stream()
                        .filter(e -> e.type() == BeingEvent.Type.READING_CHANGED).count(),
                "the slip out of sight is narrated exactly once");
        assertEquals(Being.Awareness.REMEMBERED, only().awareness());

        world.hidden.remove(walker); // steps out the other side
        List<BeingEvent> after = tickN(30);
        assertTrue(after.stream().noneMatch(e -> e.type() == BeingEvent.Type.SPOTTED),
                "the observer never thought they left, so no re-spot either");
        assertEquals(Being.Awareness.SEEN, only().awareness());
    }

    @Test
    void theLingerExpiresIntoLost() {
        BeingId ghost = world.addPerson("Gone", new Pos(0, 64, 5), 5.0, Being.Activity.IDLE);
        tickN(2);
        world.hidden.add(ghost);
        List<BeingEvent> events = tickN(BeingSensorCore.lingerTicks() + 60);

        assertEquals(1, events.stream().filter(e -> e.type() == BeingEvent.Type.LOST).count());
        assertTrue(sensor.beings().isEmpty(), "forgotten — the linger is a grace, not forever");
    }

    @Test
    void activityChangesEmitOnTheAttentionBeat() {
        BeingId busy = world.addPerson("Busy", new Pos(0, 64, 3), 3.0, Being.Activity.IDLE);
        tickN(2);
        world.set(busy, new Pos(0, 64, 3), 3.0, Being.Activity.MINING);
        List<BeingEvent> events = tickN(10);

        assertEquals(1, events.stream()
                .filter(e -> e.type() == BeingEvent.Type.READING_CHANGED).count());
        BeingEvent change = events.stream()
                .filter(e -> e.type() == BeingEvent.Type.READING_CHANGED).findFirst().orElseThrow();
        assertEquals(Being.Activity.IDLE, change.was().activity());
        assertEquals(Being.Activity.MINING, change.being().activity());
    }

    @Test
    void rememberedReadingsStayFrozen() {
        BeingId walled = world.addPerson("Frozen", new Pos(0, 64, 5), 5.0, Being.Activity.MINING);
        tickN(2);
        world.hidden.add(walled);
        world.set(walled, new Pos(0, 64, 5), 5.0, Being.Activity.IDLE); // world moves on, unseen
        List<BeingEvent> events = tickN(40);

        assertTrue(events.stream().noneMatch(e -> e.type() == BeingEvent.Type.READING_CHANGED
                        && e.being().activity() != Being.Activity.MINING),
                "nothing announces an activity that cannot be seen");
        assertEquals(Being.Activity.MINING, only().activity(),
                "the remembered reading is the LAST LIVE one, frozen");
    }

    @Test
    void earsDoNotReadTheUnseenBodysActivity() {
        // Behind them and walled: the ear is the only channel. The world's visual classifier
        // says AT_CRAFTING, but the SOUND said moving — and sound is all there is.
        BeingId noisy = world.addPerson("Rustler", new Pos(0, 64, -5), 5.0,
                Being.Activity.AT_CRAFTING);
        world.hidden.add(noisy);
        sensor.heard(FakeBeingWorld.person(noisy, "Rustler", new Pos(0, 64, -5), 5.0,
                Being.Locomotion.WALKING, false, false, false, Being.Activity.IDLE), now, false);
        List<BeingEvent> events = tickN(BeingSensorCore.HEARD_FRESH_TICKS - 2);

        assertEquals(Being.Activity.IDLE, only().activity(),
                "the heard reading holds — no visual classification through walls");
        assertEquals(Being.Locomotion.WALKING, only().locomotion(),
                "the steps' story stands on the legs axis");
        assertTrue(events.stream().noneMatch(e -> e.type() == BeingEvent.Type.READING_CHANGED
                        && (e.being().activity() != e.was().activity()
                                || e.being().locomotion() != e.was().locomotion())),
                "and no phantom axis flips while the ear is the only channel");
    }

    @Test
    void earsCannotSeeAGazeOrACrouch() {
        BeingId starer = world.addPerson("Starer", new Pos(0, 64, -5), 5.0, Being.Activity.MINING);
        world.hidden.add(starer);
        sensor.heard(FakeBeingWorld.person(starer, "Starer", new Pos(0, 64, -5), 5.0,
                Being.Locomotion.STILL, true, true, true, Being.Activity.MINING), now, false);
        tickN(2);

        Being heard = only();
        assertFalse(heard.watching(), "gaze is an eyes-only read");
        assertFalse(heard.sneaking(), "posture is an eyes-only read");
        assertFalse(heard.aimedAt(), "and so is a bow's bearing");
        assertEquals(Being.Activity.MINING, heard.activity(), "but the sound's story stands");
    }

    @Test
    void aHeardActivityDecaysWhenTheSoundStops() {
        BeingId knocker = world.addPerson("Knocker", new Pos(0, 64, -5), 5.0, Being.Activity.MINING);
        world.hidden.add(knocker);
        sensor.heard(FakeBeingWorld.person(knocker, "Knocker", new Pos(0, 64, -5), 5.0,
                Being.Locomotion.WALKING, false, false, false, Being.Activity.MINING), now, false);
        List<BeingEvent> events = tickN(BeingSensorCore.heardActivityDecayTicks() + 20);

        assertEquals(Being.Activity.IDLE, only().activity(),
                "no more knocks: 'mining' faded to 'just someone there' — never stuck forever");
        assertEquals(Being.Locomotion.STILL, only().locomotion(), "the legs faded with it");
        assertTrue(events.stream().anyMatch(e -> e.type() == BeingEvent.Type.READING_CHANGED
                        && e.was().activity() == Being.Activity.MINING),
                "and the fade is a narrated event, not a silent edit");
    }

    @Test
    void soundDoesNotSayWho() {
        BeingId walled = world.addPerson("Masked", new Pos(0, 64, 5), 5.0, Being.Activity.MINING);
        world.hidden.add(walled); // in front but walled: only the ear knows them
        sensor.heard(world.bodies.get(walled), now, false);
        tickN(3);
        Being heardOnly = only();
        assertEquals("someone", heardOnly.knownAs(), "ears carry no name");
        assertEquals(Being.Identified.NONE, heardOnly.identified());
        assertEquals(Being.Kind.UNKNOWN, heardOnly.kind(), "a step can't even say what KIND");

        world.hidden.remove(walled); // the wall comes down — the eyes catch up
        List<BeingEvent> events = tickN(10);
        assertEquals(1, events.stream()
                .filter(e -> e.type() == BeingEvent.Type.RECOGNIZED).count());
        assertEquals("Masked", only().knownAs(), "a face at last");
    }

    @Test
    void aVoiceNamesOnlyTheSpeciesSoAHeardPersonStaysSomeone() {
        BeingId cryer = world.addPerson("Cryer", new Pos(0, 64, 5), 5.0, Being.Activity.IDLE);
        world.hidden.add(cryer);
        sensor.heard(world.bodies.get(cryer), now, true); // a hurt "oof" — a voice
        tickN(2);

        Being heard = only();
        assertEquals(Being.Identified.SPECIES, heard.identified(), "the voice climbed one rung");
        assertEquals(Being.Kind.PERSON, heard.kind(), "…and a person-voice says person-kind");
        assertEquals("someone", heard.knownAs(),
                "but a person's species names nobody — 'someone' until SEEN");
    }

    @Test
    void theConeHasAnUpAndDown() {
        world.addPerson("Hoverer", new Pos(0, 74, 3), 10.4, Being.Activity.IDLE);
        List<BeingEvent> level = tickN(15);
        assertTrue(level.isEmpty(), "looking level: the sky is out of view");

        List<BeingEvent> up = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            up.addAll(sensor.tick(self, 0.0, -70.0, now++, world)); // craning up
        }
        assertEquals(1, up.stream().filter(e -> e.type() == BeingEvent.Type.SPOTTED).count(),
                "craning up brings them into the cone");
    }

    @Test
    void theAxesCoexist() {
        BeingId snacker = world.addPerson("Snacker", new Pos(0, 64, 3), 3.0, Being.Activity.EATING);
        world.bodies.put(snacker, FakeBeingWorld.person(snacker, "Snacker", new Pos(0, 64, 3), 3.0,
                Being.Locomotion.WALKING, false, false, false, Being.Activity.EATING));
        tickN(2);

        Being being = only();
        assertEquals(Being.Activity.EATING, being.activity());
        assertEquals(Being.Locomotion.WALKING, being.locomotion());
        assertEquals("eating, walking", being.tell("them"));
    }

    @Test
    void attentionScalesWithDistance() {
        BeingId near = world.addPerson("Near", new Pos(0, 64, 2), 2.0, Being.Activity.IDLE);
        BeingId far = world.addPerson("Far", new Pos(0, 64, 23), 23.0, Being.Activity.IDLE);
        tickN(120);

        int nearReads = world.readCounts.getOrDefault(near, 0);
        int farReads = world.readCounts.getOrDefault(far, 0);
        assertTrue(nearReads > farReads * 3,
                "point-blank attention vs edge-of-range glances: " + nearReads + " vs " + farReads);
    }

    // --- the widened organ -----------------------------------------------------------------------

    @Test
    void aCreatureSpottedReadsItsSpeciesOnSight() {
        world.addCreature(Being.Kind.MONSTER, "zombie", false, true, new Pos(0, 64, 6), 6.0);
        List<BeingEvent> events = tickN(1);

        assertEquals(1, events.stream().filter(e -> e.type() == BeingEvent.Type.SPOTTED).count());
        Being zombie = only();
        assertEquals(Being.Kind.MONSTER, zombie.kind());
        assertEquals("a zombie", zombie.knownAs());
        assertTrue(zombie.aggressive());
        assertEquals(Being.Identified.INDIVIDUAL, zombie.identified());
    }

    @Test
    void theLadderClimbsStepThenVoiceThenSight() {
        BeingId walled = world.addCreature(Being.Kind.MONSTER, "zombie", false, true,
                new Pos(0, 64, 5), 5.0);
        world.hidden.add(walled);

        sensor.heard(world.bodies.get(walled), now, false); // a step behind the wall
        tickN(2);
        Being something = only();
        assertEquals("someone", something.knownAs(), "a step says position, nothing else");
        assertEquals(Being.Kind.UNKNOWN, something.kind());
        assertFalse(something.aggressive(), "nothing downstream fears an unmade-out something");

        List<BeingEvent> voiced = new ArrayList<>();
        sensor.heard(world.bodies.get(walled), now, true); // the groan
        voiced.addAll(tickN(2));
        Being named = only();
        assertEquals(1, voiced.stream().filter(e -> e.type() == BeingEvent.Type.RECOGNIZED).count(),
                "the voice is a recognition moment");
        assertEquals("a zombie", named.knownAs(), "the groan names the species through the wall");
        assertEquals(Being.Kind.MONSTER, named.kind());
        assertTrue(named.aggressive(), "and the flee math may now price it");
        assertEquals(Being.Awareness.HEARD, named.awareness(), "sight was never involved");

        world.hidden.remove(walled); // the wall comes down
        List<BeingEvent> seen = tickN(10);
        assertEquals(1, seen.stream().filter(e -> e.type() == BeingEvent.Type.RECOGNIZED).count(),
                "sight finishes the ladder — one more rung, one more event");
        assertEquals(Being.Identified.INDIVIDUAL, only().identified());
    }

    @Test
    void aMonsterClosingInReadsApproachingAndACalmOneClears() {
        BeingId zombie = world.addCreature(Being.Kind.MONSTER, "zombie", false, true,
                new Pos(0, 64, 8), 8.0);
        tickN(1);
        for (int i = 0; i < 40; i++) { // walks straight at them, ~0.15 blocks/tick
            double d = 8.0 - 0.15 * i;
            world.move(zombie, new Pos(0, 64, (int) Math.round(d)), d);
            tickN(1);
        }
        assertTrue(only().approaching(), "a sustained closing trend reads as approaching");

        List<BeingEvent> whileHolding = tickN(40); // stops dead at that distance
        assertFalse(only().approaching(), "holding distance releases the flag");
        assertTrue(whileHolding.stream().anyMatch(e -> e.type() == BeingEvent.Type.READING_CHANGED),
                "and the flip is an event the future flee hook consumes");
    }

    @Test
    void aWanderingCowNeverFlagsApproaching() {
        BeingId cow = world.addCreature(Being.Kind.PASSIVE, "cow", true, false,
                new Pos(0, 64, 8), 8.0);
        tickN(1);
        for (int i = 0; i < 40; i++) {
            double d = 8.0 - 0.15 * i;
            world.move(cow, new Pos(0, 64, (int) Math.round(d)), d);
            tickN(1);
        }
        assertFalse(only().approaching(), "closing in is a menace read — not for the calm");
    }

    @Test
    void threeHeadCollapseIntoAHerdAndTwoStayIndividuals() {
        world.addCreature(Being.Kind.PASSIVE, "cow", true, false, new Pos(0, 64, 6), 6.0);
        world.addCreature(Being.Kind.PASSIVE, "cow", true, false, new Pos(2, 64, 7), 7.0);
        tickN(12); // a sweep beat groups; two cows stay two beings
        assertEquals(2, sensor.beings().size(), "1-2 head: remember as each (decision: Luiz)");

        world.addCreature(Being.Kind.PASSIVE, "cow", true, false, new Pos(4, 64, 8), 9.0);
        List<BeingEvent> events = tickN(12);
        Being herd = only();
        assertTrue(herd.herd(), "3+ head: one perception");
        assertEquals(3, herd.count());
        assertEquals("a herd of cows", herd.knownAs());
        assertEquals(Being.Kind.PASSIVE, herd.kind());
        assertTrue(events.stream().anyMatch(e -> e.type() == BeingEvent.Type.SPOTTED
                        && e.being().herd()),
                "the herd's formation is a spotting of its own");
    }

    @Test
    void herdsSplitBySpeciesAndByDistance() {
        world.addCreature(Being.Kind.PASSIVE, "cow", true, false, new Pos(0, 64, 6), 6.0);
        world.addCreature(Being.Kind.PASSIVE, "cow", true, false, new Pos(2, 64, 7), 7.0);
        world.addCreature(Being.Kind.PASSIVE, "cow", true, false, new Pos(4, 64, 8), 9.0);
        world.addCreature(Being.Kind.PASSIVE, "sheep", true, false, new Pos(0, 64, 8), 8.0);
        world.addCreature(Being.Kind.PASSIVE, "sheep", true, false, new Pos(2, 64, 9), 9.0);
        world.addCreature(Being.Kind.PASSIVE, "sheep", true, false, new Pos(4, 64, 10), 10.0);
        tickN(12);

        List<Being> beings = sensor.beings();
        assertEquals(2, beings.size(), "one herd per species, even grazing interleaved: " + beings);
        assertTrue(beings.stream().allMatch(b -> b.herd() && b.count() == 3));
    }

    @Test
    void aHerdDissolvesBackToIndividualsWhenItShrinksBelowThree() {
        BeingId a = world.addCreature(Being.Kind.PASSIVE, "cow", true, false, new Pos(0, 64, 6), 6.0);
        world.addCreature(Being.Kind.PASSIVE, "cow", true, false, new Pos(2, 64, 7), 7.0);
        world.addCreature(Being.Kind.PASSIVE, "cow", true, false, new Pos(4, 64, 8), 9.0);
        tickN(12);
        assertTrue(only().herd());

        world.remove(a); // one wanders off the world entirely
        tickN(BeingSensorCore.lingerTicks() + 40);
        List<Being> beings = sensor.beings();
        assertEquals(2, beings.size(), "the herd dissolved into the two remaining: " + beings);
        assertTrue(beings.stream().noneMatch(Being::herd));
    }

    @Test
    void theRayBudgetDefersButNeverSkips() {
        for (int i = 0; i < 60; i++) {
            // A crowd fanned out ahead, all inside cone and radius, none herdable.
            world.addCreature(Being.Kind.MONSTER, "zombie", false, true,
                    new Pos(i % 8 - 4, 64, 6 + i / 8), 6.0 + i / 8);
        }
        tickN(1);
        assertTrue(world.maxRaysInOneTick <= 16,
                "one tick never pays the whole crowd: " + world.maxRaysInOneTick);
        assertTrue(sensor.beings().size() < 60, "the backlog genuinely deferred");

        tickN(7);
        assertEquals(60, sensor.beings().size(),
                "…but everyone is noticed within a handful of ticks — deferred, never skipped");
        assertTrue(world.maxRaysInOneTick <= 20,
                "and the per-tick spend stayed near max(base, work/4): " + world.maxRaysInOneTick);
    }

    // --- scripted bodies -------------------------------------------------------------------------

    /** Scripted bodies: position/kind settable, walls per body, ray and read counters. */
    private static final class FakeBeingWorld implements BeingWorld {
        final Map<BeingId, BeingReading> bodies = new LinkedHashMap<>();
        final Set<BeingId> hidden = new HashSet<>();
        final Map<BeingId, Integer> readCounts = new HashMap<>();
        int rayChecks;
        int raysThisTick;
        int maxRaysInOneTick;

        /** The test loop's tick edge for the per-tick ray counter. */
        void newTick() {
            raysThisTick = 0;
        }

        static BeingReading person(BeingId id, String name, Pos pos, double distance,
                                   Being.Locomotion legs, boolean sneaking, boolean watching,
                                   boolean aimedAt, Being.Activity activity) {
            return new BeingReading(id, Being.Kind.PERSON, "person", name, null, false, pos,
                    distance, legs, sneaking, watching, aimedAt, false, Being.Gear.NONE,
                    activity);
        }

        BeingId addPerson(String name, Pos pos, double distance, Being.Activity activity) {
            BeingId id = BeingId.of(UUID.randomUUID());
            bodies.put(id, person(id, name, pos, distance, Being.Locomotion.STILL,
                    false, false, false, activity));
            return id;
        }

        BeingId addCreature(Being.Kind kind, String species, boolean herdAnimal,
                            boolean aggressive, Pos pos, double distance) {
            BeingId id = BeingId.of(UUID.randomUUID());
            bodies.put(id, new BeingReading(id, kind, species, "", null, herdAnimal, pos,
                    distance, Being.Locomotion.STILL, false, false, false, aggressive,
                    Being.Gear.NONE, Being.Activity.IDLE));
            return id;
        }

        void set(BeingId id, Pos pos, double distance, Being.Activity activity) {
            BeingReading r = bodies.get(id);
            bodies.put(id, new BeingReading(id, r.kind(), r.species(), r.name(), r.profession(),
                    r.herdAnimal(), pos, distance, Being.Locomotion.STILL, false, false, false,
                    r.aggressive(), r.gear(), activity));
        }

        void move(BeingId id, Pos pos, double distance) {
            BeingReading r = bodies.get(id);
            bodies.put(id, new BeingReading(id, r.kind(), r.species(), r.name(), r.profession(),
                    r.herdAnimal(), pos, distance, r.locomotion(), r.sneaking(), r.watching(),
                    r.aimedAt(), r.aggressive(), r.gear(), r.activity()));
        }

        void remove(BeingId id) {
            bodies.remove(id);
        }

        @Override
        public List<BeingReading> candidates() {
            return List.copyOf(bodies.values());
        }

        @Override
        public @Nullable BeingReading reading(BeingId id) {
            readCounts.merge(id, 1, Integer::sum);
            return bodies.get(id);
        }

        @Override
        public boolean inSight(BeingId id) {
            rayChecks++;
            raysThisTick++;
            maxRaysInOneTick = Math.max(maxRaysInOneTick, raysThisTick);
            return !hidden.contains(id);
        }
    }
}
