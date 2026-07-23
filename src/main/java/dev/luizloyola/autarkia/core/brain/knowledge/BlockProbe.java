package dev.luizloyola.autarkia.core.brain.knowledge;

import dev.luizloyola.autarkia.core.brain.sense.Pos;

/**
 * Perception's only window onto the world. The compat layer implements it over the live level
 * (heightmap lookups, tag checks, a voxel ray from the eyes); tests implement it over fake grids.
 * Every call is assumed to cost one block read against the sensor's per-tick wallet — the reason
 * the interface is this narrow.
 */
public interface BlockProbe {
    /**
     * The y of the topmost surface block at this column (motion-blocking heightmap), or
     * {@link Integer#MIN_VALUE} when the column is out of reach (unloaded chunk).
     */
    int surfaceY(int x, int z);

    /** What stands at the cell. Out-of-reach cells return {@link BlockKind#UNKNOWN}. */
    BlockKind at(int x, int y, int z);

    /**
     * Can the person see this cell from her eyes? One voxel walk, air/leaves/water transparent;
     * fired once per <em>discovery</em>, never per column.
     */
    boolean visibleFromEyes(Pos target);
}
