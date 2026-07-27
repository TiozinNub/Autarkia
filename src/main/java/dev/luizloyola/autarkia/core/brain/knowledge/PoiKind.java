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
    WATER(8),
    /**
     * Animals remembered by GENERAL LOCATION: 3+ head of one species is a herd memory (anchor =
     * centroid, {@code units} = head count), 1–2 are individual memories of the same kind, so the
     * brain can weigh two lone cows against a herd of six. {@code detail} carries the species and
     * merging is detail-aware. Merge radius 0 because {@code HerdNoter} owns all matching — its
     * expand-recenter rule needs the remembered AREA inflated 2–3×, not a fixed anchor radius.
     */
    HERD(0);

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
