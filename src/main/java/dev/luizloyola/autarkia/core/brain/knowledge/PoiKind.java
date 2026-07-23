package dev.luizloyola.autarkia.core.brain.knowledge;

/**
 * What a remembered point of interest <em>is</em> — the vocabulary of the knowledge store (see
 * {@code docs/superpowers/specs/2026-07-23-poi-perception-design.md}). A new kind is a constant
 * here plus an expansion rule in the sensor; the store itself never changes.
 */
public enum PoiKind {
    /**
     * A connected stand of logs + leaves — grove semantics: touching canopies fuse into one
     * memory, possibly spanning several trunks. Anchor = the lowest log nearest the discovery
     * seed.
     */
    TREE(2),
    /**
     * A body of surface water. Anchor = a shore-adjacent surface cell. The wide merge radius
     * coalesces partial re-discoveries of one body (a lake met from two sides) into one memory.
     */
    WATER(8);

    private final int mergeRadius;

    PoiKind(int mergeRadius) {
        this.mergeRadius = mergeRadius;
    }

    /**
     * Chebyshev distance within which two anchors of this kind are the same memory —
     * {@code note()} replaces rather than accumulates. Sized for 2×2 trunks re-seen from another
     * side (TREE) and shoreline re-discoveries (WATER).
     */
    public int mergeRadius() {
        return this.mergeRadius;
    }
}
