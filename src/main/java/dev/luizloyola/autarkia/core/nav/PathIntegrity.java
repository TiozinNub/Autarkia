package dev.luizloyola.autarkia.core.nav;

import java.util.ArrayList;
import java.util.List;

/**
 * The completion-critical cells of a path <em>edge</em> — the "relevant blocks" the follower
 * watches a few nodes ahead (see {@code Navigator}'s integrity check). Keyed on the move, and for
 * the common case the cells the feet cross, not merely the destination:
 *
 * <ul>
 *   <li><b>{@link MoveType#WALK}</b> (step, diagonal, stride): every cell on the
 *       {@link #lineCells Bresenham line} keeps {@link CellType#GROUND} at {@code y-1} and
 *       {@link CellType#PASSABLE} at {@code y..y+height-1}. Watching only the destination missed
 *       blocks pulled from the <em>middle</em> of a stride: a bridge crossed in 3-cell strides
 *       leaves two of every three deck cells between waypoints.</li>
 *   <li><b>{@link MoveType#SWIM}</b>: the destination feet cell stays {@link CellType#WATER}, body
 *       clear above the waterline.</li>
 *   <li><b>{@link MoveType#LEAP}</b>: the landing plus the flight arc — takeoff headroom and a
 *       clear body-height+1 corridor over every gap column, so a wall built into the arc is
 *       caught.</li>
 *   <li><b>{@link MoveType#DROP}, {@link MoveType#JUMP}</b>: destination standability only; the
 *       drop shaft and the jumped block are left to the reactive stuck/stray net.</li>
 * </ul>
 *
 * <p>Re-derived from waypoint geometry rather than recorded by the search, so it mirrors
 * {@link Pathfinder}'s level-move generators — tolerable because {@code isStandable} is the
 * engine's most stable invariant, and {@code PathIntegrityTest} pins the shape.
 */
public final class PathIntegrity {
    private PathIntegrity() {}

    /**
     * The cells whose classification the edge from {@code from} to {@code to} depends on, given the
     * agent's body height — see the class doc for the per-move rule.
     */
    public static List<CellNeed> edgeNeeds(Waypoint from, Waypoint to, AgentProfile profile) {
        int height = profile.height();
        List<CellNeed> needs = new ArrayList<>();
        if (to.move() == MoveType.SWIM) {
            // Surface float: feet in water, the rest of the body clear above the waterline.
            needs.add(new CellNeed(to.x(), to.y(), to.z(), CellType.WATER));
            for (int i = 1; i < height; i++) {
                needs.add(new CellNeed(to.x(), to.y() + i, to.z(), CellType.PASSABLE));
            }
            return needs;
        }
        if (to.move() == MoveType.WALK) {
            // Level ground move: watch the standable floor + body under every cell the feet cross,
            // at this waypoint's level (walks/diagonals/strides are all same-level).
            for (int[] cell : lineCells(from.x(), from.z(), to.x(), to.z())) {
                addStandable(needs, cell[0], to.y(), cell[1], height);
            }
            return needs;
        }
        if (to.move() == MoveType.LEAP) {
            // A leap is the landing PLUS the flight arc — a wall built into the arc stops it
            // mid-air. Mirrors {@code Pathfinder.leapNeighbor}: takeoff headroom over the launch
            // cell, and a clear body-height+1 corridor (the arc rises a block) over every gap
            // column — cardinal and same-level, so those are the cells strictly between the
            // waypoints, all at the takeoff's y. The gap floor is not watched: filling
            // it does not break the leap, only its cost.
            addStandable(needs, to.x(), to.y(), to.z(), height); // the landing
            int y = from.y();
            needs.add(new CellNeed(from.x(), y + height, from.z(), CellType.PASSABLE)); // takeoff headroom
            int sx = Integer.signum(to.x() - from.x());
            int sz = Integer.signum(to.z() - from.z());
            for (int gx = from.x() + sx, gz = from.z() + sz; gx != to.x() || gz != to.z(); gx += sx, gz += sz) {
                for (int i = 0; i <= height; i++) {
                    needs.add(new CellNeed(gx, y + i, gz, CellType.PASSABLE));
                }
            }
            return needs;
        }
        // Other vertical move (drop, jump): destination standability only. The shaft a drop falls
        // through / the block a jump clears are v1-out-of-scope, left to the reactive stuck/stray net.
        addStandable(needs, to.x(), to.y(), to.z(), height);
        return needs;
    }

    /** Appends the standability cells of feet-cell {@code (x,y,z)}: floor {@code GROUND}, body clear. */
    private static void addStandable(List<CellNeed> needs, int x, int y, int z, int height) {
        needs.add(new CellNeed(x, y - 1, z, CellType.GROUND));
        for (int i = 0; i < height; i++) {
            needs.add(new CellNeed(x, y + i, z, CellType.PASSABLE));
        }
    }

    /**
     * The horizontal cells a straight segment from {@code (x0,z0)} to {@code (x1,z1)} crosses — an
     * integer Bresenham line, deterministic and allocation-light, core's own rather than
     * Minecraft's. Endpoints included, one cell per step: a pure diagonal steps corner-to-corner,
     * the feet crossing the diagonal cells and not the grazed corners.
     */
    static List<int[]> lineCells(int x0, int z0, int x1, int z1) {
        List<int[]> cells = new ArrayList<>();
        int dx = Math.abs(x1 - x0);
        int dz = Math.abs(z1 - z0);
        int sx = x0 < x1 ? 1 : -1;
        int sz = z0 < z1 ? 1 : -1;
        int err = dx - dz;
        int x = x0;
        int z = z0;
        while (true) {
            cells.add(new int[]{x, z});
            if (x == x1 && z == z1) {
                return cells;
            }
            int e2 = 2 * err;
            if (e2 > -dz) {
                err -= dz;
                x += sx;
            }
            if (e2 < dx) {
                err += dx;
                z += sz;
            }
        }
    }
}
