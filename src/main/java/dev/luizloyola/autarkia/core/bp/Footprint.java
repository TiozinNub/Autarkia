package dev.luizloyola.autarkia.core.bp;

import dev.luizloyola.anima.core.territory.ChunkKey;
import dev.luizloyola.autarkia.core.bp.BuildPlan.CellKind;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.SortedSet;

/**
 * The ground a placed plan stands on, corners inclusive — what a party's area must hold for it to be
 * theirs (docs/superpowers/specs/2026-10-01-home-area-design.md, decision 8).
 */
public record Footprint(int minX, int minZ, int maxX, int maxZ) {

    /** The same corners {@code Placer} checks before it writes a block. */
    public static Footprint of(int anchorX, int anchorZ, BuildPlan plan, Placement placement) {
        int width = placement.width(plan.width(), plan.depth());
        int depth = placement.depth(plan.width(), plan.depth());
        return new Footprint(anchorX + Placement.offset(0, width), anchorZ + Placement.offset(0, depth),
                anchorX + Placement.offset(width - 1, width), anchorZ + Placement.offset(depth - 1, depth));
    }

    public SortedSet<ChunkKey> chunks(String dimension) {
        return ChunkKey.covering(dimension, minX, minZ, maxX, maxZ);
    }

    /**
     * The open cell on the outside of the plan's first door, as {@code {dx, layer, dz}} from the
     * anchor — where a yard goes, so the HOME a building founds starts at its door. A door has two
     * open sides; the outside is the one farther from the plan's middle, which holds for a door set
     * back behind a porch as well as one in the outer wall. Empty when no door has an open side.
     */
    public static Optional<int[]> doorstep(BuildPlan plan, Placement placement, Dictionary dictionary) {
        Map<Long, CellKind> kinds = new HashMap<>();
        int[] door = new int[3];
        boolean[] found = {false};
        plan.forEach(placement, (dx, layer, dz, kind, state) -> {
            kinds.put(key(dx, layer, dz), kind);
            if (!found[0] && state != null && dictionary.block(state.block())
                    .map(info -> info.is("minecraft:doors")).orElse(false)) {
                found[0] = true;
                door[0] = dx;
                door[1] = layer;
                door[2] = dz;
            }
        });
        if (!found[0]) {
            return Optional.empty();
        }
        Footprint at = of(0, 0, plan, placement);
        double midX = (at.minX + at.maxX) / 2.0;
        double midZ = (at.minZ + at.maxZ) / 2.0;
        int[] best = null;
        double farthest = -1;
        for (int[] side : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            int x = door[0] + side[0];
            int z = door[2] + side[1];
            CellKind kind = kinds.get(key(x, door[1], z));
            if (kind == CellKind.BLOCK || kind == CellKind.TERRAIN) {
                continue;
            }
            double distance = Math.hypot(x - midX, z - midZ);
            if (distance > farthest) {
                farthest = distance;
                best = new int[] {x, door[1], z};
            }
        }
        return Optional.ofNullable(best);
    }

    private static long key(int dx, int layer, int dz) {
        return ((long) (dx & 0xFFFF) << 32) | ((long) (layer & 0xFFFF) << 16) | (dz & 0xFFFF);
    }
}
