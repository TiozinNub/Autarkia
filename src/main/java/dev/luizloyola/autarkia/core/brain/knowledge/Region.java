package dev.luizloyola.autarkia.core.brain.knowledge;

import dev.luizloyola.autarkia.core.brain.sense.Pos;

/**
 * An inclusive axis-aligned box of whole-block cells — the compact "where" of a remembered region
 * POI: precise enough to re-walk the blocks and test membership for invalidation, tiny enough that
 * dozens serialize to nothing. The per-block coordinate set lives only in the sensor's transient
 * claim index.
 */
public record Region(Pos min, Pos max) {
    public Region {
        if (min.x() > max.x() || min.y() > max.y() || min.z() > max.z()) {
            throw new IllegalArgumentException("region min " + min + " exceeds max " + max);
        }
    }

    public static Region of(Pos cell) {
        return new Region(cell, cell);
    }

    /** Whether the cell lies inside this box (inclusive on all faces). */
    public boolean contains(Pos p) {
        return p.x() >= min.x() && p.x() <= max.x()
                && p.y() >= min.y() && p.y() <= max.y()
                && p.z() >= min.z() && p.z() <= max.z();
    }

    /** The smallest box containing both this box and the cell — how a growing scan widens it. */
    public Region including(Pos p) {
        if (contains(p)) {
            return this;
        }
        return new Region(
                new Pos(Math.min(min.x(), p.x()), Math.min(min.y(), p.y()), Math.min(min.z(), p.z())),
                new Pos(Math.max(max.x(), p.x()), Math.max(max.y(), p.y()), Math.max(max.z(), p.z())));
    }
}
