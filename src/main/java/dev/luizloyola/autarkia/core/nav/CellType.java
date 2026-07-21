package dev.luizloyola.autarkia.core.nav;

/**
 * The pathfinder's entire vocabulary for the world. The compat layer collapses real blockstates
 * to one of these per cell; collision shapes, block ids and fluids stay behind that seam.
 */
public enum CellType {
    /** Empty enough to occupy: air, grass, flowers — anything with no collision. */
    PASSABLE,
    /** Solid with a sturdy top: blocks bodies, and the cell above it can be stood in. */
    GROUND,
    /**
     * Blocks bodies but not standable (fences, walls, open trapdoors, unmodeled partial collision)
     * — also the out-of-bounds sentinel: unknown space must stay unwalkable.
     */
    OBSTACLE,
    /** Harmful to touch or stand on: lava, fire, cactus, magma. Never entered, never a floor. */
    DANGER,
    /** Unused by the v1 walker (treated as impassable), reserved for swimming. */
    WATER
}
