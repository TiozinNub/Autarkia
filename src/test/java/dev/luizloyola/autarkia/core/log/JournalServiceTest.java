package dev.luizloyola.autarkia.core.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.autarkia.core.person.PersonId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;

/**
 * The pure {@link JournalService} — per-person rings, the two retention bounds (line cap on write,
 * age on sweep), and the synchronous subscriber fan-out the file sink will hang on. A mutable fake
 * clock stands in for game time, so no Minecraft instance is needed.
 */
class JournalServiceTest {

    /** A game clock we can advance by hand; returned entries are stamped from it. */
    private static final class FakeClock implements LongSupplier {
        long now;

        @Override
        public long getAsLong() {
            return now;
        }
    }

    private static PersonId person() {
        return PersonId.of(UUID.randomUUID());
    }

    @Test
    void recentReturnsChronologicalNewestLastStampedWithTheClock() {
        FakeClock clock = new FakeClock();
        JournalService journal = new JournalService(clock);
        PersonId bob = person();

        clock.now = 10;
        journal.record(bob, Category.BRAIN, "wander (10,10,10)", "start");
        clock.now = 25;
        journal.record(bob, Category.PATHFIND, "target(10,10,10)", "success 10 nodes");

        List<Entry> recent = journal.recent(bob, 10);
        assertEquals(2, recent.size());
        assertEquals(new Entry(10, Category.BRAIN, "wander (10,10,10)", "start"), recent.get(0));
        assertEquals(new Entry(25, Category.PATHFIND, "target(10,10,10)", "success 10 nodes"), recent.get(1));
    }

    @Test
    void lineCapDropsTheOldestOnWrite() {
        FakeClock clock = new FakeClock();
        JournalService journal = new JournalService(clock, 3, JournalService.defaultMaxAgeTicks());
        PersonId bob = person();

        for (int i = 0; i < 5; i++) {
            clock.now = i;
            journal.record(bob, Category.BODY, "health", "line " + i);
        }

        // Cap is 3: only the newest three survive, oldest-first.
        List<Entry> recent = journal.recent(bob, 10);
        assertEquals(List.of("line 2", "line 3", "line 4"), details(recent));
    }

    @Test
    void recentClampsToWhatIsAsked() {
        FakeClock clock = new FakeClock();
        JournalService journal = new JournalService(clock);
        PersonId bob = person();
        for (int i = 0; i < 5; i++) {
            clock.now = i;
            journal.record(bob, Category.BODY, "health", "line " + i);
        }

        assertEquals(List.of("line 3", "line 4"), details(journal.recent(bob, 2))); // newest two
        assertTrue(journal.recent(bob, 0).isEmpty());                               // non-positive
    }

    @Test
    void sweepDropsLinesOlderThanTheAgeBoundAndForgetsEmptyPeople() {
        FakeClock clock = new FakeClock();
        JournalService journal = new JournalService(clock, 256, 50); // max age 50 ticks
        PersonId bob = person();
        PersonId alice = person();

        clock.now = 0;
        journal.record(bob, Category.BODY, "health", "old");
        clock.now = 60;
        journal.record(bob, Category.BODY, "health", "recent");
        clock.now = 10;
        journal.record(alice, Category.BODY, "health", "aliceOld");

        clock.now = 100; // cutoff = 100 - 50 = 50; anything with tick < 50 goes (old, aliceOld)
        journal.sweep();

        assertEquals(List.of("recent"), details(journal.recent(bob, 10)));
        assertTrue(journal.recent(alice, 10).isEmpty(), "Alice's only line aged out; forgotten");
    }

    @Test
    void subscribersSeeEveryEntryEvenOnesTheCapWillEvict() {
        FakeClock clock = new FakeClock();
        JournalService journal = new JournalService(clock, 2, JournalService.defaultMaxAgeTicks());
        PersonId bob = person();

        List<String> seen = new ArrayList<>();
        journal.subscribe((who, entry) -> seen.add(who + ":" + entry.detail()));

        for (int i = 0; i < 5; i++) {
            clock.now = i;
            journal.record(bob, Category.BODY, "health", "line " + i);
        }

        // The sink gets the full firehose, whatever the ring evicts.
        assertEquals(5, seen.size());
        assertEquals(bob + ":line 0", seen.get(0));
        assertEquals(bob + ":line 4", seen.get(4));
        assertEquals(2, journal.recent(bob, 10).size());
    }

    @Test
    void ringsArePerPersonSoOnePersonNeverEvictsAnother() {
        FakeClock clock = new FakeClock();
        JournalService journal = new JournalService(clock, 2, JournalService.defaultMaxAgeTicks());
        PersonId bob = person();
        PersonId alice = person();

        journal.record(alice, Category.BRAIN, "wander", "alice-1");
        for (int i = 0; i < 10; i++) { // Bob floods his own ring
            clock.now = i;
            journal.record(bob, Category.BODY, "health", "bob " + i);
        }

        assertEquals(List.of("alice-1"), details(journal.recent(alice, 10)));
        assertEquals(2, journal.recent(bob, 10).size());
    }

    @Test
    void unknownPersonReadsEmpty() {
        JournalService journal = new JournalService(new FakeClock());
        assertTrue(journal.recent(person(), 10).isEmpty());
    }

    private static List<String> details(List<Entry> entries) {
        return entries.stream().map(Entry::detail).toList();
    }
}
