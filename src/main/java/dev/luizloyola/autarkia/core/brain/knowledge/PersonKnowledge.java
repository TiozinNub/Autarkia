package dev.luizloyola.autarkia.core.brain.knowledge;

import dev.luizloyola.autarkia.core.brain.sense.Pos;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One person's remembered POIs: per kind, an anchor-keyed map with {@link #note} (I saw something),
 * {@link #refresh} (still there) and {@link #forget} (gone). No clock of its own — callers stamp
 * game time into the {@link PoiMemory} they pass.
 *
 * <p>Bounded like a memory: each kind keeps at most {@link #maxPerKind()} entries, and noting one
 * more evicts the stalest (oldest {@code lastSeenTick}).
 *
 * <p>Noting merges rather than duplicating: a new memory within its kind's
 * {@link PoiKind#mergeRadius()} (Chebyshev) of an existing one <em>replaces</em> it, the fresher
 * expansion knowing the current shape better. Insertion order is preserved for deterministic
 * iteration.
 */
public final class PersonKnowledge {
    /**
     * Memory capacity per {@link PoiKind} — {@code perception.knowledge_max_per_kind}, read
     * through the config on every use so {@code reload} retunes live Persons. Must sit ABOVE the
     * trees a person works among: at 64, an 81-tree grid churned its far corners between forget
     * and rediscover (2026-07-27: 56 notice events for one tree).
     */
    public static int maxPerKind() {
        return dev.luizloyola.autarkia.core.config.Config.get()
                .i(dev.luizloyola.autarkia.core.config.Knob.KNOWLEDGE_MAX_PER_KIND);
    }

    private final Map<PoiKind, Map<Pos, PoiMemory>> byKind = new EnumMap<>(PoiKind.class);
    /**
     * TRANSIENT avoid-marks: anchors that are true but not worth retrying right now (an
     * unworkable tree). Never serialized (a fresh boot retries clean), and consulted only by
     * method selection; the memory itself stays.
     */
    private final Map<PoiKind, Map<Pos, Long>> avoidedUntil = new EnumMap<>(PoiKind.class);

    /**
     * Records a belief: merges into an existing same-kind entry within merge radius (the new
     * memory wins — see class doc), otherwise inserts, evicting the stalest entry of that kind
     * if at capacity. Returns the stored memory for chaining.
     */
    public PoiMemory note(PoiMemory memory) {
        Objects.requireNonNull(memory, "memory");
        Map<Pos, PoiMemory> entries = entriesFor(memory.kind());
        Pos merged = findWithin(entries, memory);
        if (merged != null) {
            entries.remove(merged);
        } else if (entries.size() >= maxPerKind()) {
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

    /** Marks an anchor as not-worth-retrying until the given game time. Transient. */
    public void avoid(PoiKind kind, Pos anchor, long untilTick) {
        avoidedUntil.computeIfAbsent(kind, k -> new HashMap<>()).put(anchor, untilTick);
    }

    /** Whether the anchor is currently avoided — consult with the same clock memories carry. */
    public boolean isAvoided(PoiKind kind, Pos anchor, long now) {
        Map<Pos, Long> marks = avoidedUntil.get(kind);
        Long until = marks == null ? null : marks.get(anchor);
        return until != null && until > now;
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

    /**
     * The anchor of an existing SAME-IDENTITY entry within the kind's merge radius, or null.
     * Merging never crosses details (a cow flock never merges into the sheep flock beside it) nor
     * individuals (two pigs in one cell are still two pigs); detail-free kinds compare {@code ""}
     * to {@code ""} and null to null.
     */
    private static Pos findWithin(Map<Pos, PoiMemory> entries, PoiMemory memory) {
        int radius = memory.kind().mergeRadius();
        for (Map.Entry<Pos, PoiMemory> entry : entries.entrySet()) {
            if (!entry.getValue().detail().equals(memory.detail())
                    || !Objects.equals(entry.getValue().individual(), memory.individual())) {
                continue;
            }
            Pos existing = entry.getKey();
            int dx = Math.abs(existing.x() - memory.anchor().x());
            int dy = Math.abs(existing.y() - memory.anchor().y());
            int dz = Math.abs(existing.z() - memory.anchor().z());
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
