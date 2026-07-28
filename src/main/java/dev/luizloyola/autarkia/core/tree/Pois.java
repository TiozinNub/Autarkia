package dev.luizloyola.autarkia.core.tree;

import dev.luizloyola.anima.core.brain.knowledge.PoiKind;

/**
 * The kinds of place an Autarkia settler thinks worth remembering. Declared here, not in
 * Anima: the library owns the store, the growth machinery and the merge rule; the vocabulary
 * is ours.
 *
 * <p>Registered on class init, and the instances are canonical — {@code Pois.TREE} is the
 * same object the store, the growth rule and the saved file all mean.
 */
public final class Pois {

    /**
     * One tree, however fused its canopy: the mass is scanned whole and split per trunk by
     * {@link TreeShape}, so a grove of three is three memories. Anchor = the lowest base cell
     * nearest the discovery seed.
     *
     * <p>Merge radius 1 is the distance that clusters base cells into a trunk, so
     * a 2×2 giant re-seen from another corner re-anchors by at most 1 and nothing further
     * apart ever merges.
     */
    public static final PoiKind TREE = PoiKind.register("tree", 1, " logs");

    /**
     * A body of surface water. Anchor = a shore-adjacent surface cell. The wide merge radius
     * coalesces partial re-discoveries of one body (a lake met from two sides) into one memory.
     */
    public static final PoiKind WATER = PoiKind.register("water", 8, "");

    private Pois() {
    }

    /** Touching this class registers the kinds. Called from the mod initializer. */
    public static void init() {
    }
}
