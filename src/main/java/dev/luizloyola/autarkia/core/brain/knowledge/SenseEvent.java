package dev.luizloyola.autarkia.core.brain.knowledge;

import dev.luizloyola.autarkia.core.brain.sense.Pos;

/**
 * Something perception just did — the sensor's tick output, for the mod layer to narrate
 * (journal lines, the POI viewer's discovery chat). Refreshes are not events:
 * re-seeing what you already believe is the common, silent case. The two decline outcomes
 * ARE events, though — "why didn't they notice that?" must have a printable answer (the
 * project's debuggability rule), so a blocked ray and a rejected growth both leave a line.
 *
 * @param anchor the belief's anchor for NOTED/FORGOT; the probed surface cell (hypothesis
 *               seed) for OVERLOOKED/DISMISSED
 * @param memory the noted belief; null for every other type
 */
public record SenseEvent(Type type, PoiKind kind, Pos anchor, PoiMemory memory) {
    public enum Type {
        /** A new belief entered the store. */
        NOTED,
        /** A belief was invalidated (the world changed under its claims). */
        FORGOT,
        /** A hypothesis died at the confirm-ray: something interesting, but it can't be seen. */
        OVERLOOKED,
        /** A growth completed and was rejected by its rule (a woodpile, a roofed tree). */
        DISMISSED
    }

    static SenseEvent noted(PoiMemory memory) {
        return new SenseEvent(Type.NOTED, memory.kind(), memory.anchor(), memory);
    }

    static SenseEvent forgot(PoiKind kind, Pos anchor) {
        return new SenseEvent(Type.FORGOT, kind, anchor, null);
    }

    static SenseEvent overlooked(PoiKind kind, Pos seed) {
        return new SenseEvent(Type.OVERLOOKED, kind, seed, null);
    }

    static SenseEvent dismissed(PoiKind kind, Pos seed) {
        return new SenseEvent(Type.DISMISSED, kind, seed, null);
    }
}
