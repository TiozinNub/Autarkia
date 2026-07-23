package dev.luizloyola.autarkia.core.log;

import dev.luizloyola.autarkia.core.person.PersonId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;

/**
 * Every person's debug log, written and read through one world-scoped service keyed by
 * {@link PersonId}. Pure {@code core}: it owns the in-memory rings and the retention policy, never
 * a file handle, thread or clock — the {@code mod} wrapper injects a game-time clock and a file
 * sink, as {@code PersonDirectory} wraps {@code PersonRegistry}.
 *
 * <p>Keyed by identity, not entity: a {@code PersonId} outlives (and never requires) an in-world
 * entity, so an offline-simulated person records through the same call and the log does not go
 * blind when a chunk unloads.
 *
 * <p>Two retention bounds:
 * <ul>
 *   <li><em>line cap</em> — at most {@link #maxEntriesPerPerson} lines each, oldest dropped on
 *       every {@link #record}, so memory is hard-bounded and, the rings being per-person, a chatty
 *       person can never evict a quiet one's history.</li>
 *   <li><em>age sweep</em> — {@link #sweep()} drops lines older than {@link #maxAgeTicks}, called
 *       on a cadence by the caller, never on the write path.</li>
 * </ul>
 * The rings are the ephemeral tier; the durable archive is the file the sink writes.
 *
 * <p>{@link #subscribe} sinks are notified synchronously on the caller's (game) thread and must
 * only <em>enqueue</em>, never block on I/O. A sink sees every entry, including ones a later
 * cap/sweep evicts.
 *
 * <p>Unsynchronised: records arrive on the server thread today (brain/nav/body all tick from
 * {@code serverAiStep}). If offline sim later records off-thread, this is the one place to guard.
 */
public final class JournalService {

    /** Per-person line cap (see the class doc): a person's ring keeps at most this many entries. */
    public static final int DEFAULT_MAX_ENTRIES_PER_PERSON = 256;

    /** Default age bound for {@link #sweep()}: ~10 game-minutes at 20 ticks/second. */
    public static final long DEFAULT_MAX_AGE_TICKS = 20L * 60 * 10;

    private final LongSupplier clock;
    private final int maxEntriesPerPerson;
    private final long maxAgeTicks;

    /** One ring per person; entries within a ring are in clock (append) order, oldest at the head. */
    private final Map<PersonId, Ring> byPerson = new HashMap<>();
    /** Sinks notified as each entry lands — the file writer hangs here (see the class doc). */
    private final List<BiConsumer<PersonId, Entry>> sinks = new ArrayList<>();

    /** A service with the default retention bounds; {@code clock} is the game-time source. */
    public JournalService(LongSupplier clock) {
        this(clock, DEFAULT_MAX_ENTRIES_PER_PERSON, DEFAULT_MAX_AGE_TICKS);
    }

    public JournalService(LongSupplier clock, int maxEntriesPerPerson, long maxAgeTicks) {
        this.clock = Objects.requireNonNull(clock, "clock");
        if (maxEntriesPerPerson < 1) {
            throw new IllegalArgumentException("maxEntriesPerPerson must be >= 1: " + maxEntriesPerPerson);
        }
        if (maxAgeTicks < 0) {
            throw new IllegalArgumentException("maxAgeTicks must be >= 0: " + maxAgeTicks);
        }
        this.maxEntriesPerPerson = maxEntriesPerPerson;
        this.maxAgeTicks = maxAgeTicks;
    }

    /**
     * File one line under {@code who}: stamp it with the current tick, append it to that person's
     * ring (evicting the oldest if the cap is now exceeded), then notify every sink. Cheap and
     * non-blocking — safe to call from the tick.
     */
    public void record(PersonId who, Category category, String event, String detail) {
        Objects.requireNonNull(who, "who");
        Entry entry = new Entry(clock.getAsLong(), category, event, detail);
        byPerson.computeIfAbsent(who, id -> new Ring()).add(entry, maxEntriesPerPerson);
        for (BiConsumer<PersonId, Entry> sink : sinks) {
            sink.accept(who, entry);
        }
    }

    /**
     * The last {@code max} lines recorded for {@code who}, oldest-first — the debug command's
     * readout. Empty for an unknown person or a non-positive {@code max}; fewer than {@code max}
     * if that is all there is.
     */
    public List<Entry> recent(PersonId who, int max) {
        Ring ring = byPerson.get(who);
        return ring == null ? List.of() : ring.recent(max);
    }

    /**
     * A {@link PersonJournal} view bound to {@code who} — records without repeating the id
     * ({@code journal.record(BRAIN, "wander", "start")}). A flyweight; hand out as many as
     * convenient.
     */
    public PersonJournal forPerson(PersonId who) {
        return new PersonJournal(this, who);
    }

    /**
     * Register a sink to receive every future entry, {@code (who, entry)}, as it is recorded. See
     * the class doc for the enqueue-only contract.
     */
    public void subscribe(BiConsumer<PersonId, Entry> sink) {
        sinks.add(Objects.requireNonNull(sink, "sink"));
    }

    /**
     * Drops lines older than {@link #maxAgeTicks}, forgetting any person left with none. Entries
     * are in clock order, so each ring evicts a run from its head. Never the write path.
     */
    public void sweep() {
        long cutoff = clock.getAsLong() - maxAgeTicks;
        Iterator<Map.Entry<PersonId, Ring>> it = byPerson.entrySet().iterator();
        while (it.hasNext()) {
            Ring ring = it.next().getValue();
            ring.evictOlderThan(cutoff);
            if (ring.isEmpty()) {
                it.remove();
            }
        }
    }

    // --- internals -------------------------------------------------------------------------------

    /**
     * One person's bounded line buffer: an append-at-tail, evict-from-head deque. Entries go in in
     * clock order (the service stamps them from a monotonic game clock), so the head is always the
     * oldest — which is what lets both the line cap and the age sweep evict from the front.
     */
    private static final class Ring {
        private final Deque<Entry> entries = new ArrayDeque<>();

        void add(Entry entry, int cap) {
            entries.addLast(entry);
            while (entries.size() > cap) {
                entries.removeFirst();
            }
        }

        void evictOlderThan(long cutoffTick) {
            while (!entries.isEmpty() && entries.peekFirst().tick() < cutoffTick) {
                entries.removeFirst();
            }
        }

        List<Entry> recent(int max) {
            if (max <= 0) {
                return List.of();
            }
            int size = entries.size();
            if (size <= max) {
                return List.copyOf(entries); // head->tail == oldest->newest
            }
            List<Entry> out = new ArrayList<>(max);
            int skip = size - max;
            int i = 0;
            for (Entry entry : entries) {
                if (i++ >= skip) {
                    out.add(entry);
                }
            }
            return List.copyOf(out);
        }

        boolean isEmpty() {
            return entries.isEmpty();
        }
    }
}
