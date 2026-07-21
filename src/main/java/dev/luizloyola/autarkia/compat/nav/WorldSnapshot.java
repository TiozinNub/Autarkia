package dev.luizloyola.autarkia.compat.nav;

import dev.luizloyola.autarkia.core.nav.CellType;
import dev.luizloyola.autarkia.core.nav.NavGrid;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/**
 * An immutable box of {@link CellType} classifications baked from real blockstates, and the
 * thread-safety seam: {@link #capture} reads the live {@link Level} and <b>must run on the server
 * thread</b>, but its {@code byte[]} never changes, so a worker thread can search it while the
 * world ticks on.
 *
 * <p>Block changes after capture are invisible to it: paths are short-lived and re-requested on
 * stuck.
 */
public final class WorldSnapshot implements NavGrid {
    private static final CellType[] TYPES = CellType.values();

    private final int minX;
    private final int minY;
    private final int minZ;
    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    private final byte[] cells;

    private WorldSnapshot(int minX, int minY, int minZ, int sizeX, int sizeY, int sizeZ, byte[] cells) {
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.cells = cells;
    }

    /**
     * Bakes the inclusive box {@code [min, max]} of {@code level} into a snapshot. Server thread
     * only (live chunk reads). The y range is clamped to the level's build height; unloaded chunks
     * classify as {@link CellType#OBSTACLE} (checked per column, so no chunk loads are triggered).
     */
    public static WorldSnapshot capture(Level level, BlockPos min, BlockPos max) {
        int minY = Math.max(min.getY(), level.getMinY());
        int maxY = Math.min(max.getY(), level.getMaxY());
        int sizeX = max.getX() - min.getX() + 1;
        int sizeY = Math.max(maxY - minY + 1, 1);
        int sizeZ = max.getZ() - min.getZ() + 1;
        byte[] cells = new byte[sizeX * sizeY * sizeZ];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = 0; x < sizeX; x++) {
            for (int z = 0; z < sizeZ; z++) {
                pos.set(min.getX() + x, minY, min.getZ() + z);
                boolean loaded = level.isLoaded(pos);
                for (int y = 0; y < sizeY; y++) {
                    pos.setY(minY + y);
                    CellType type = loaded ? classify(level, pos) : CellType.OBSTACLE;
                    cells[(y * sizeZ + z) * sizeX + x] = (byte) type.ordinal();
                }
            }
        }
        return new WorldSnapshot(min.getX(), minY, min.getZ(), sizeX, sizeY, sizeZ, cells);
    }

    /** Collapses one blockstate to the navigation vocabulary. The order matters — see comments. */
    private static CellType classify(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            return CellType.PASSABLE;
        }
        // Harmful before everything else: fire has no collision (would read PASSABLE), magma is a
        // full sturdy block (would read GROUND) — both must classify DANGER first.
        if (isHarmful(state)) {
            return CellType.DANGER;
        }
        FluidState fluid = state.getFluidState();
        if (fluid.is(FluidTags.LAVA)) {
            return CellType.DANGER;
        }
        if (state.getCollisionShape(level, pos).isEmpty()) {
            // No collision: air-like plants — or the inside of a water column (kelp, seagrass,
            // source blocks). Waterlogged solids fall through to the sturdy-top check instead.
            return fluid.is(FluidTags.WATER) ? CellType.WATER : CellType.PASSABLE;
        }
        if (state.isFaceSturdy(level, pos, Direction.UP)) {
            return CellType.GROUND;
        }
        // Collides but can't be stood on square: fences, walls, open trapdoors, bottom-half
        // shapes we don't model.
        return CellType.OBSTACLE;
    }

    /** Blocks that hurt to touch or stand on, beyond what fluids cover. */
    private static boolean isHarmful(BlockState state) {
        return state.is(BlockTags.FIRE)
                || state.is(BlockTags.CAMPFIRES)
                || state.is(Blocks.CACTUS)
                || state.is(Blocks.MAGMA_BLOCK)
                || state.is(Blocks.SWEET_BERRY_BUSH)
                || state.is(Blocks.WITHER_ROSE)
                || state.is(Blocks.POWDER_SNOW);
    }

    @Override
    public CellType cell(int x, int y, int z) {
        int ix = x - this.minX;
        int iy = y - this.minY;
        int iz = z - this.minZ;
        if (ix < 0 || ix >= this.sizeX || iy < 0 || iy >= this.sizeY || iz < 0 || iz >= this.sizeZ) {
            return CellType.OBSTACLE;
        }
        return TYPES[this.cells[(iy * this.sizeZ + iz) * this.sizeX + ix]];
    }

    /** Whether the inclusive box {@code [min, max]} lies fully inside this snapshot. */
    public boolean covers(BlockPos min, BlockPos max) {
        return min.getX() >= this.minX && max.getX() < this.minX + this.sizeX
                && min.getY() >= this.minY && max.getY() < this.minY + this.sizeY
                && min.getZ() >= this.minZ && max.getZ() < this.minZ + this.sizeZ;
    }
}
