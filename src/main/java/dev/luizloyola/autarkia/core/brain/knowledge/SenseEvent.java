package dev.luizloyola.autarkia.core.brain.knowledge;

import dev.luizloyola.autarkia.core.brain.sense.Pos;

/**
 * Something perception just learned — the sensor's tick output for the mod layer to narrate.
 * Refreshes are not events: re-seeing what you already believe is the silent case.
 *
 * @param memory the noted belief; null on FORGOT (the belief is already gone)
 */
public record SenseEvent(Type type, PoiKind kind, Pos anchor, PoiMemory memory) {
    public enum Type {
        NOTED,
        FORGOT
    }

    static SenseEvent noted(PoiMemory memory) {
        return new SenseEvent(Type.NOTED, memory.kind(), memory.anchor(), memory);
    }

    static SenseEvent forgot(PoiKind kind, Pos anchor) {
        return new SenseEvent(Type.FORGOT, kind, anchor, null);
    }
}
