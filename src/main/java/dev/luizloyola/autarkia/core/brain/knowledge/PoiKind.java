package dev.luizloyola.autarkia.core.brain.knowledge;

/**
 * What a remembered point of interest <em>is</em> — the vocabulary of the knowledge store (see
 * {@code docs/superpowers/specs/2026-07-23-poi-perception-design.md}). A new kind is a constant
 * here plus an expansion rule in the sensor; the store itself never changes.
 */
public enum PoiKind {
    /**
     * One tree, however fused its canopy is with its neighbours': the mass is scanned whole and
     * split per trunk by {@link TreeShape}, so a grove of three is three memories. Anchor = the
     * lowest base cell nearest the discovery seed. Merge radius is the same 1 that clusters base
     * cells into a trunk, so a 2×2 giant re-seen from another corner re-anchors by at most 1.
     */
    TREE(1),
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
     * Chebyshev distance (max per-axis difference) within which two anchors of this kind are the
     * same memory — {@code note()} replaces rather than accumulates.
     */
    public int mergeRadius() {
        return this.mergeRadius;
    }
}
