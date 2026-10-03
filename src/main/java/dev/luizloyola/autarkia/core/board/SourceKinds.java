package dev.luizloyola.autarkia.core.board;

import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.inv.ItemSpec;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What kind of place a registered source is found at — trees for logs, stone patches for stone —
 * so an expedition searching for one knows which glimpses mean it, and how much of it one brings
 * home ({@link Haul}). Registered beside the producer.
 */
public final class SourceKinds {

    /** How much an expedition brings home of a resource, by how it is found (expedition spec). */
    public enum Haul {
        /** Effectively infinite, stone: enough for some time, never all that is found. */
        PLENTY,
        /** Renewable but local, wood: the open needs and a margin. */
        RENEWABLE,
        /** Scarce or seed-like, cane: everything found, the open need the least of it. */
        SCARCE
    }

    private static final Map<ItemSpec, PoiKind> KINDS = new ConcurrentHashMap<>();
    private static final Map<ItemSpec, Haul> HAULS = new ConcurrentHashMap<>();

    private SourceKinds() {
    }

    public static void register(ItemSpec source, PoiKind kind) {
        register(source, kind, Haul.PLENTY);
    }

    public static void register(ItemSpec source, PoiKind kind, Haul haul) {
        KINDS.put(source, kind);
        HAULS.put(source, haul);
    }

    public static Optional<PoiKind> of(ItemSpec source) {
        return Optional.ofNullable(KINDS.get(source));
    }

    /** A resource not in the table is stone-like (expedition spec, item 4). */
    public static Haul haulOf(ItemSpec source) {
        return HAULS.getOrDefault(source, Haul.PLENTY);
    }
}
