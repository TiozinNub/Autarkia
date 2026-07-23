package dev.luizloyola.autarkia.core.brain.knowledge;

import dev.luizloyola.autarkia.core.brain.sense.Pos;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One person's remembered POIs: per kind, an anchor-keyed map with {@link #note},
 * {@link #refresh} and {@link #forget}. No clock of its own — callers stamp game time into the
 * {@link PoiMemory} they pass.
 *
 * <p><b>Bounded like a memory, not a database.</b> Each kind keeps at most
 * {@link #MAX_PER_KIND} entries; noting one more evicts the <em>stalest</em> (oldest
 * {@code lastSeenTick}).
 *
 * <p><b>Noting merges, never duplicates.</b> A new memory within its kind's
 * {@link PoiKind#mergeRadius()} (Chebyshev) of an existing one <em>replaces</em> it: the fresher
 * scan knows the current shape, and keeping both would count the wood twice. Insertion order is
 * preserved for deterministic iteration.
 */
public final class PersonKnowledge {
    public static final int MAX_PER_KIND = 64;

    private final Map<PoiKind, Map<Pos, PoiMemory>> byKind = new EnumMap<>(PoiKind.class);

    /**
     * Records a belief: merges into an existing same-kind entry within merge radius (the new
     * memory wins — see class doc), otherwise inserts, evicting the stalest entry of that kind
     * if at capacity. Returns the stored memory for chaining.
     */
    public PoiMemory note(PoiMemory memory) {
        Objects.requireNonNull(memory, "memory");
        Map<Pos, PoiMemory> entries = entriesFor(memory.kind());
        Pos merged = findWithin(entries, memory.anchor(), memory.kind().mergeRadius());
        if (merged != null) {
            entries.remove(merged);
        } else if (entries.size() >= MAX_PER_KIND) {
            evictStalest(entries);
        }
        entries.put(memory.anchor(), memory);
        return memory;
    }

    /**
     * Re-confirms the entry anchored exactly at {@code anchor} as seen {@code now}. Returns
     * false when no such entry exists (the sensor may race its own eviction; a miss is not an
     * error).
     */
    public boolean refresh(PoiKind kind, Pos anchor, long now) {
        Map<Pos, PoiMemory> entries = byKind.get(kind);
        if (entries == null) {
            return false;
        }
        PoiMemory memory = entries.get(anchor);
        if (memory == null) {
            return false;
        }
        entries.put(anchor, memory.seenAt(now));
        return true;
    }

    /** Drops the entry anchored exactly at {@code anchor}; false when there was none. */
    public boolean forget(PoiKind kind, Pos anchor) {
        Map<Pos, PoiMemory> entries = byKind.get(kind);
        return entries != null && entries.remove(anchor) != null;
    }

    /**
     * The remembered entry of this kind nearest to {@code from} (squared euclidean over anchors).
     * Distance only — the method prices staleness, weighing {@code age()} against distance per the
     * brain design's cost model.
     */
    public Optional<PoiMemory> nearest(PoiKind kind, Pos from) {
        Map<Pos, PoiMemory> entries = byKind.get(kind);
        if (entries == null || entries.isEmpty()) {
            return Optional.empty();
        }
        PoiMemory best = null;
        long bestDist = Long.MAX_VALUE;
        for (PoiMemory memory : entries.values()) {
            long dist = distanceSq(memory.anchor(), from);
            if (dist < bestDist) {
                bestDist = dist;
                best = memory;
            }
        }
        return Optional.of(best);
    }

    /** All entries of a kind, insertion-ordered, unmodifiable — the debug command's view. */
    public Collection<PoiMemory> all(PoiKind kind) {
        Map<Pos, PoiMemory> entries = byKind.get(kind);
        return entries == null
                ? Collections.emptyList()
                : Collections.unmodifiableCollection(entries.values());
    }

    /** Total remembered entries across kinds. */
    public int size() {
        int size = 0;
        for (Map<Pos, PoiMemory> entries : byKind.values()) {
            size += entries.size();
        }
        return size;
    }

    private Map<Pos, PoiMemory> entriesFor(PoiKind kind) {
        return byKind.computeIfAbsent(kind, k -> new LinkedHashMap<>());
    }

    /** The anchor of an existing entry within Chebyshev {@code radius} of {@code anchor}, or null. */
    private static Pos findWithin(Map<Pos, PoiMemory> entries, Pos anchor, int radius) {
        for (Pos existing : entries.keySet()) {
            int dx = Math.abs(existing.x() - anchor.x());
            int dy = Math.abs(existing.y() - anchor.y());
            int dz = Math.abs(existing.z() - anchor.z());
            if (Math.max(dx, Math.max(dy, dz)) <= radius) {
                return existing;
            }
        }
        return null;
    }

    private static void evictStalest(Map<Pos, PoiMemory> entries) {
        Pos stalest = null;
        long oldest = Long.MAX_VALUE;
        for (Map.Entry<Pos, PoiMemory> entry : entries.entrySet()) {
            if (entry.getValue().lastSeenTick() < oldest) {
                oldest = entry.getValue().lastSeenTick();
                stalest = entry.getKey();
            }
        }
        entries.remove(stalest);
    }

    private static long distanceSq(Pos a, Pos b) {
        long dx = a.x() - b.x();
        long dy = a.y() - b.y();
        long dz = a.z() - b.z();
        return dx * dx + dy * dy + dz * dz;
    }
}
