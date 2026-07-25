package dev.luizloyola.autarkia.core.log;

import dev.luizloyola.autarkia.core.config.Config;
import dev.luizloyola.autarkia.core.config.Knob;
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
 * The one place every person's debug log is written and read: one world-scoped service keyed by
 * {@link PersonId}, so an offline-simulated person records through the same call and the log does
 * not go blind when a chunk unloads. Pure {@code core} — it owns the rings and the retention
 * policy, never a file handle, a thread or a clock; the {@code mod} wrapper injects the game clock
 * and attaches the file sink.
 *
 * <p>Two retention bounds. The line cap ({@link #maxEntriesPerPerson()}) is enforced on every
 * {@link #record} and the rings are per-person, so one chatty person neither unbounds memory nor
 * evicts a quiet one's history; the age sweep ({@link #sweep()}, {@link #maxAgeTicks()}) runs on
 * the caller's cadence, never on the write path. The rings are the ephemeral tier; the durable
 * archive is the file the sink writes.
 *
 * <p>A {@link #subscribe}d sink is notified synchronously on the caller's (game) thread and must
 * only <em>enqueue</em>, never block on I/O; it sees every entry, including ones a later cap or
 * sweep evicts.
 *
 * <p>Unsynchronised: records arrive on the server thread today (brain/nav/body tick from
 * {@code serverAiStep}) — the one place to guard if offline sim ever records off-thread.
 */
public final class JournalService {

    /** Per-person line cap (see the class doc): a person's ring keeps at most this many entries. */
    public static int defaultMaxEntriesPerPerson() {
        return Config.get().i(Knob.JOURNAL_MAX_ENTRIES);
    }

    /** Default age bound for {@link #sweep()}: ~10 game-minutes at 20 ticks/second. */
    public static long defaultMaxAgeTicks() {
        return Config.get().i(Knob.JOURNAL_MAX_AGE_TICKS);
    }

    private final LongSupplier clock;
    /** A caller-pinned line cap, or {@code null} to track the configured default live. */
    private final Integer maxEntriesOverride;
    /** A caller-pinned age bound, or {@code null} to track the configured default live. */
    private final Long maxAgeOverride;

    /** One ring per person; entries within a ring are in clock (append) order, oldest at the head. */
    private final Map<PersonId, Ring> byPerson = new HashMap<>();
    /** Sinks notified as each entry lands — the file writer hangs here (see the class doc). */
    private final List<BiConsumer<PersonId, Entry>> sinks = new ArrayList<>();

    /**
     * A service that follows the configured retention bounds — and keeps following them, so a
     * {@code /autarkia config reload} retunes a world's journal without a restart. {@code clock}
     * is the game-time source.
     */
    public JournalService(LongSupplier clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.maxEntriesOverride = null;
        this.maxAgeOverride = null;
    }

    /** A service pinned to explicit bounds, ignoring configuration — the shape tests want. */
    public JournalService(LongSupplier clock, int maxEntriesPerPerson, long maxAgeTicks) {
        this.clock = Objects.requireNonNull(clock, "clock");
        if (maxEntriesPerPerson < 1) {
            throw new IllegalArgumentException("maxEntriesPerPerson must be >= 1: " + maxEntriesPerPerson);
        }
        if (maxAgeTicks < 0) {
            throw new IllegalArgumentException("maxAgeTicks must be >= 0: " + maxAgeTicks);
        }
        this.maxEntriesOverride = maxEntriesPerPerson;
        this.maxAgeOverride = maxAgeTicks;
    }

    /** The line cap in force: this service's pin, else the configured default. */
    public int maxEntriesPerPerson() {
        return maxEntriesOverride == null ? defaultMaxEntriesPerPerson() : maxEntriesOverride;
    }

    /** The age bound in force: this service's pin, else the configured default. */
    public long maxAgeTicks() {
        return maxAgeOverride == null ? defaultMaxAgeTicks() : maxAgeOverride;
    }

    /**
     * File one line under {@code who}: stamp it with the current tick, append it to that person's
     * ring (evicting the oldest if the cap is now exceeded), then notify every sink. Cheap and
     * non-blocking — safe to call from the tick.
     */
    public void record(PersonId who, Category category, String event, String detail) {
        Objects.requireNonNull(who, "who");
        Entry entry = new Entry(clock.getAsLong(), category, event, detail);
        byPerson.computeIfAbsent(who, id -> new Ring()).add(entry, maxEntriesPerPerson());
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
     * Drop every line older than {@link #maxAgeTicks()} and forget any person left with none.
     * Cheap: entries are in clock order, so each ring evicts a run from its head and stops. Call
     * on a slow cadence, never on the write path.
     */
    /** Drops a person's ring outright — the dev purge path (the durable file is untouched). */
    public void drop(PersonId who) {
        byPerson.remove(who);
    }

    public void sweep() {
        long cutoff = clock.getAsLong() - maxAgeTicks();
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
