package dev.luizloyola.autarkia.core.brain.knowledge;

/**
 * The block classifications perception can tell apart — everything the crescent probe and the
 * growth rules need, and nothing more. The compat layer maps real blockstates onto these, the way
 * {@code WorldSnapshot} bakes states into {@code CellType} for the pathfinder.
 */
public enum BlockKind {
    AIR,
    /** Any log/stem the tree rule treats as trunk material. */
    LOG,
    /**
     * A leaf block that GREW — one that would decay if its tree were felled. Placed leaves never
     * decay and the compat probe hands them over as {@link #OTHER}.
     */
    LEAVES,
    /** A water source or flowing water. */
    WATER,
    /** Everything else — stone, dirt, crops, chests… nothing perception reacts to yet. */
    OTHER,
    /**
     * Out of reach — unloaded chunk or outside the world. Growth stops here and marks the region
     * {@code partial}.
     */
    UNKNOWN;
}
