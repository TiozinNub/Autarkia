package dev.luizloyola.autarkia.core.person;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** One journal line per window of walk-over pickups, not one per tick that caught something. */
class PickupTallyTest {

    private static final String LITTER = "minecraft:leaf_litter";
    private static final String LOG = "minecraft:oak_log";

    /** Runs the tally the way Person's tick does: catch, then ask whether the window is due. */
    private static List<String> lines(PickupTally tally, long from, long to, String id, int every) {
        List<String> lines = new ArrayList<>();
        for (long now = from; now < to; now++) {
            if ((now - from) % every == 0) {
                tally.add(id, 1, now);
            }
            String line = tally.due(now);
            if (line != null) {
                lines.add(line);
            }
        }
        return lines;
    }

    @Test
    void manySingleCatchesAcrossTicksAreOneLinePerWindow() {
        PickupTally tally = new PickupTally();
        List<String> lines = new ArrayList<>();
        for (long now = 1000; now < 1000 + PickupTally.WINDOW_TICKS; now++) {
            tally.add(LITTER, 1, now);
            if (now % 100 == 0) {
                tally.add(LOG, 2, now);
            }
            String line = tally.due(now);
            if (line != null) {
                lines.add(line);
            }
        }
        assertEquals(List.of(), lines, "nothing is written before the window closes");
        String closing = tally.due(1000 + PickupTally.WINDOW_TICKS);
        assertEquals("picked up 1200×" + LITTER + ", 24×" + LOG, closing);
        assertNull(tally.due(1000 + PickupTally.WINDOW_TICKS + 1), "the tally starts over empty");
    }

    @Test
    void aLongSweepWritesOneLinePerWindow() {
        List<String> lines = lines(new PickupTally(), 0, 10 * PickupTally.WINDOW_TICKS, LITTER, 3);
        assertEquals(9, lines.size(), "the tenth window is still open: " + lines);
        assertEquals("picked up 401×" + LITTER, lines.get(0));
    }

    @Test
    void theWindowClosesWithoutAnotherCatch() {
        PickupTally tally = new PickupTally();
        tally.add(LOG, 1, 50);
        assertNull(tally.due(50 + PickupTally.WINDOW_TICKS - 1));
        assertEquals("picked up 1×" + LOG, tally.due(50 + PickupTally.WINDOW_TICKS));
    }

    @Test
    void nothingPickedUpWritesNothing() {
        PickupTally tally = new PickupTally();
        for (long now = 0; now < 5 * PickupTally.WINDOW_TICKS; now++) {
            assertNull(tally.due(now));
        }
        assertNull(tally.drain());
    }

    @Test
    void aClockThatRanBackwardsClosesTheWindow() {
        PickupTally tally = new PickupTally();
        tally.add(LOG, 3, 10_000);
        assertEquals("picked up 3×" + LOG, tally.due(20));
    }

    /** What a restart does: the pending counts and the window's start come back, once. */
    @Test
    void aRestoredTallyFinishesTheSameWindow() {
        PickupTally before = new PickupTally();
        before.add(LITTER, 5, 100);
        before.add(LOG, 1, 400);
        PickupTally after = new PickupTally();
        after.restore(before.snapshot());
        after.add(LITTER, 2, 700);
        assertNull(after.due(100 + PickupTally.WINDOW_TICKS - 1), "the window opened before the save");
        assertEquals("picked up 7×" + LITTER + ", 1×" + LOG, after.due(100 + PickupTally.WINDOW_TICKS));
    }
}
