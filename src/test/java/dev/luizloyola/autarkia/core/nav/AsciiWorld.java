package dev.luizloyola.autarkia.core.nav;

import java.util.HashMap;
import java.util.Map;

/**
 * A hand-drawable {@link NavGrid} for tests: rows of characters form a heightmap (row index = z,
 * column index = x), so a small terrain reads like a top-down map in the test source.
 *
 * <pre>
 *   '1'..'9'  column of solid ground below height d — feet level is y = d ("111" is flat ground
 *             you walk on at y=1, "12" is a one-block step up)
 *   '#'       wall: solid at every y, never passable
 *   ' '       bottomless hole: passable at every y, nothing to land on
 *   'L'       lava pool: the surface cell (y = 0) is DANGER, solid below, open above
 *   'W'       water pool: like 'L' but WATER
 * </pre>
 *
 * Anything outside the drawn rows is {@link CellType#OBSTACLE}, per the {@link NavGrid} contract.
 * For shapes a heightmap cannot draw (ceilings, tunnels), {@link #fill} overrides a box of cells
 * with an explicit type.
 */
final class AsciiWorld implements NavGrid {
    private final String[] rows;
    private final Map<Long, CellType> overrides = new HashMap<>();

    private AsciiWorld(String[] rows) {
        this.rows = rows;
    }

    static AsciiWorld of(String... rows) {
        return new AsciiWorld(rows);
    }

    /** Overrides every cell in the inclusive box with {@code type} — for ceilings and tunnels. */
    AsciiWorld fill(int x1, int y1, int z1, int x2, int y2, int z2, CellType type) {
        for (int x = x1; x <= x2; x++) {
            for (int y = y1; y <= y2; y++) {
                for (int z = z1; z <= z2; z++) {
                    this.overrides.put(Pathfinder.pack(x, y, z), type);
                }
            }
        }
        return this;
    }

    @Override
    public CellType cell(int x, int y, int z) {
        CellType override = this.overrides.get(Pathfinder.pack(x, y, z));
        if (override != null) return override;
        if (z < 0 || z >= this.rows.length || x < 0 || x >= this.rows[z].length()) {
            return CellType.OBSTACLE;
        }
        char c = this.rows[z].charAt(x);
        switch (c) {
            case '#': return CellType.GROUND;
            case ' ': return CellType.PASSABLE;
            case 'L': return y == 0 ? CellType.DANGER : y < 0 ? CellType.GROUND : CellType.PASSABLE;
            case 'W': return y == 0 ? CellType.WATER : y < 0 ? CellType.GROUND : CellType.PASSABLE;
            default:
                if (c < '1' || c > '9') throw new IllegalArgumentException("bad map char: '" + c + "'");
                return y < c - '0' ? CellType.GROUND : CellType.PASSABLE;
        }
    }
}
