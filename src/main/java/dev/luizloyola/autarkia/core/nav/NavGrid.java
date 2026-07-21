package dev.luizloyola.autarkia.core.nav;

/**
 * The pathfinder's only view of the world: a classification per block-sized cell. Implementations
 * must be safe to read from a worker thread — in practice that means immutable snapshots
 * (the compat layer's {@code WorldSnapshot}) or fixed test grids; never a live level.
 */
public interface NavGrid {
    /**
     * Classifies the cell at these coordinates; outside the implementation's known bounds it must
     * return {@link CellType#OBSTACLE}, so the search never routes through unknown space.
     */
    CellType cell(int x, int y, int z);
}
