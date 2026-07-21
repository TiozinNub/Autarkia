package dev.luizloyola.autarkia.core.nav;

/**
 * Pure helpers over a {@link NavGrid}, shared by the engine and the (mod-layer) follower.
 */
public final class NavGrids {
    private NavGrids() {}

    /**
     * Whether a misstep out of feet-cell {@code (x,y,z)} could be catastrophic: some cardinal
     * neighbour is open at this level but has no floor within {@code maxDrop} below — a chasm —
     * or its floor is harmful (lava) or unswimmable-for-now (water). The follower slows down and
     * steers tighter while this is true of the ground it is crossing; drops within
     * {@code maxDrop} onto solid ground are everyday moves and do not count.
     */
    public static boolean isNearDeepDrop(NavGrid grid, int maxDrop, int x, int y, int z) {
        for (int i = 0; i < 4; i++) {
            int nx = x + (i == 0 ? 1 : i == 1 ? -1 : 0);
            int nz = z + (i == 2 ? 1 : i == 3 ? -1 : 0);
            if (grid.cell(nx, y, nz) != CellType.PASSABLE) {
                continue; // a wall, not a step-off
            }
            int floor = y - 1;
            int limit = y - maxDrop - 1;
            while (floor >= limit && grid.cell(nx, floor, nz) == CellType.PASSABLE) {
                floor--;
            }
            if (floor < limit) {
                return true; // open all the way past survivable depth
            }
            CellType landing = grid.cell(nx, floor, nz);
            if (landing == CellType.DANGER || landing == CellType.WATER) {
                return true;
            }
        }
        return false;
    }
}
