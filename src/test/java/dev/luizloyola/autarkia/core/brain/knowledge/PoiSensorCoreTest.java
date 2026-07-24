package dev.luizloyola.autarkia.core.brain.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.autarkia.core.brain.sense.Pos;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The assembled pipeline, end to end on a fake world: walk → notice → remember; re-sight →
 * refresh; chop → forget; hidden → never noticed; and the wallet holds every tick.
 */
class PoiSensorCoreTest {

    /** Per-tick read ceiling asserted from outside. The wallet counts probe+growth reads; the
     *  rules' own surface checks ride uncounted (bounded by region size), hence the slack. */
    private static final int READ_CEILING = PoiSensorCore.READS_PER_TICK + 40;

    private final PersonKnowledge knowledge = new PersonKnowledge();
    private final PoiSensorCore sensor = new PoiSensorCore(knowledge);
    private long now;

    /** Ticks at fixed feet until the sensor goes quiet (queue drained, growth finished). */
    private List<SenseEvent> tickUntilQuiet(FakeProbe probe, Pos feet) {
        List<SenseEvent> events = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            int before = probe.reads;
            events.addAll(sensor.tick(feet, now++, probe));
            int spent = probe.reads - before;
            assertTrue(spent <= READ_CEILING, "tick spent " + spent + " reads");
            if (spent == 0) {
                return events;
            }
        }
        throw new AssertionError("sensor never went quiet");
    }

    @Test
    void walkingPastAnOakNotesAGroveOnce() {
        FakeProbe probe = new FakeProbe();
        probe.placeOak(8, 0);
        List<SenseEvent> events = tickUntilQuiet(probe, new Pos(0, 64, 0));

        assertEquals(1, events.size(), "one grove, one discovery");
        SenseEvent noted = events.get(0);
        assertEquals(SenseEvent.Type.NOTED, noted.type());
        assertEquals(PoiKind.TREE, noted.kind());
        assertEquals(new Pos(8, 64, 0), noted.anchor());
        assertEquals(4, noted.memory().units());
        assertEquals(new Pos(8, 64, 0),
                knowledge.nearest(PoiKind.TREE, new Pos(0, 64, 0)).orElseThrow().anchor());
        assertEquals(21, sensor.claimCount(), "the grove's blocks are claimed");
    }

    @Test
    void standingStillCostsExactlyNothing() {
        FakeProbe probe = new FakeProbe();
        probe.placeOak(8, 0);
        tickUntilQuiet(probe, new Pos(0, 64, 0));

        int before = probe.reads;
        for (int i = 0; i < 50; i++) {
            sensor.tick(new Pos(0, 64, 0), now++, probe);
        }
        assertEquals(before, probe.reads, "no movement, no reads — the idle-settlement property");
    }

    @Test
    void reSightingRefreshesInsteadOfReDiscovering() {
        FakeProbe probe = new FakeProbe();
        probe.placeOak(8, 0);
        tickUntilQuiet(probe, new Pos(0, 64, 0));
        long notedAt = knowledge.all(PoiKind.TREE).iterator().next().lastSeenTick();

        tickUntilQuiet(probe, new Pos(200, 64, 0));
        List<SenseEvent> onReturn = tickUntilQuiet(probe, new Pos(0, 64, 0));

        assertTrue(onReturn.isEmpty(), "claims short-circuit: no re-discovery, no event spam");
        assertEquals(1, knowledge.size());
        assertTrue(knowledge.all(PoiKind.TREE).iterator().next().lastSeenTick() > notedAt,
                "but the belief was silently re-confirmed");
    }

    @Test
    void aChoppedGroveIsForgottenOnTheNextWalkPast() {
        FakeProbe probe = new FakeProbe();
        probe.placeOak(8, 0);
        tickUntilQuiet(probe, new Pos(0, 64, 0));
        probe.removeOak(8, 0);

        tickUntilQuiet(probe, new Pos(200, 64, 0));
        List<SenseEvent> onReturn = tickUntilQuiet(probe, new Pos(0, 64, 0));

        assertEquals(1, onReturn.size());
        assertEquals(SenseEvent.Type.FORGOT, onReturn.get(0).type());
        assertEquals(new Pos(8, 64, 0), onReturn.get(0).anchor());
        assertEquals(0, knowledge.size(), "the ghost tree is gone from her map");
        assertEquals(0, sensor.claimCount(), "and its claims swept with it");
    }

    @Test
    void aHiddenCanopyIsNeverNoticed() {
        FakeProbe probe = new FakeProbe();
        probe.placeOak(8, 0);
        // A wall she can't see past: every canopy surface cell is ray-blocked.
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                probe.hide(new Pos(8 + dx, 68, dz));
            }
        }
        List<SenseEvent> events = tickUntilQuiet(probe, new Pos(0, 64, 0));

        assertTrue(events.stream().allMatch(e -> e.type() == SenseEvent.Type.OVERLOOKED),
                "every canopy hypothesis died at the ray — and each left a debug event");
        assertFalse(events.isEmpty(), "the declines are narrated, not silent");
        assertEquals(0, knowledge.size(), "no ray, no belief — the evidence gate");
        assertEquals(0, sensor.claimCount(), "and no claims either: worth a look if she gets closer");
    }

    @Test
    void aTaskSideForgetDoesNotLeaveHerBlindToTheRegrownTree() {
        FakeProbe probe = new FakeProbe();
        probe.placeOak(8, 0);
        tickUntilQuiet(probe, new Pos(0, 64, 0));
        // A task (ChopTree's ghost path, an eviction) forgets the memory without the sensor's
        // claims hearing — a regrown sapling then stayed invisible behind the orphaned claims.
        knowledge.forget(PoiKind.TREE, new Pos(8, 64, 0));

        // The return pass: the first orphaned column drops the region's claims, which un-masks
        // the rest of the canopy for the very same sweep — re-discovery is immediate.
        tickUntilQuiet(probe, new Pos(200, 64, 0));
        List<SenseEvent> events = tickUntilQuiet(probe, new Pos(0, 64, 0));

        assertEquals(1, knowledge.size(), "the tree is believed in again");
        assertTrue(events.stream().anyMatch(e -> e.type() == SenseEvent.Type.NOTED),
                "re-discovered as a fresh grove, not masked forever");
    }

    @Test
    void aPondIsNotedAsWater() {
        FakeProbe probe = new FakeProbe();
        for (int x = 6; x <= 10; x++) {
            for (int z = -2; z <= 2; z++) {
                probe.set(x, FakeProbe.GROUND_Y, z, BlockKind.WATER);
            }
        }
        List<SenseEvent> events = tickUntilQuiet(probe, new Pos(0, 64, 0));

        assertEquals(1, events.size());
        assertEquals(PoiKind.WATER, events.get(0).kind());
        assertEquals(25, events.get(0).memory().units());
    }
}
