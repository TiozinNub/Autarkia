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
    /**
     * Swimmable liquid — one value for every water cell, surface or submerged. Impassable to a
     * land-only agent ({@link AgentProfile#canSwim()} false: neither {@link #GROUND} nor
     * {@link #PASSABLE}); a swimmer occupies it. The waterline is derived geometrically (water with
     * air above), so these same cells serve underwater routing without a second value.
     */
    WATER
}
