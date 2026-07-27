package dev.luizloyola.autarkia.compat.sense;

import dev.luizloyola.autarkia.core.brain.knowledge.BlockKind;
import dev.luizloyola.autarkia.core.brain.knowledge.BlockProbe;
import dev.luizloyola.autarkia.core.brain.sense.Pos;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * The {@link BlockProbe} over a live level, from one body's eyes: real blockstates collapsed to the
 * {@link BlockKind} vocabulary. Server thread only; unloaded columns answer
 * {@link Integer#MIN_VALUE}/{@link BlockKind#UNKNOWN} without triggering a chunk load. Surface
 * questions ride the {@code MOTION_BLOCKING} heightmap — one array lookup, so a column probes O(1)
 * — counting fluids (a lake's surface is its water) and leaves (a canopy's, its top leaf).
 */
public final class LevelProbe implements BlockProbe {
    private final LivingEntity eyes;
    private final Level level;

    public LevelProbe(LivingEntity eyes) {
        this.eyes = eyes;
        this.level = eyes.level();
    }

    @Override
    public int surfaceY(int x, int z) {
        if (!this.level.isLoaded(new BlockPos(x, this.level.getMinY(), z))) {
            return Integer.MIN_VALUE;
        }
        // getHeight returns the first FREE y above the top motion-blocking block.
        int top = this.level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
        // Snow layers ride the heightmap and mask whatever carries them — a snow-capped canopy
        // would read OTHER and its tree never be hypothesized.
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, top, z);
        while (top > this.level.getMinY() && this.level.getBlockState(pos).is(Blocks.SNOW)) {
            pos.setY(--top);
        }
        return top;
    }

    @Override
    public BlockKind at(int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        if (!this.level.isLoaded(pos)) {
            return BlockKind.UNKNOWN;
        }
        BlockState state = this.level.getBlockState(pos);
        if (state.isAir()) {
            return BlockKind.AIR;
        }
        if (state.is(BlockTags.LOGS)) {
            return BlockKind.LOG;
        }
        if (state.is(BlockTags.LEAVES)) {
            // Persistent leaves were PLACED, not grown, so they read as ordinary blocks: nothing
            // hypothesizes a tree from them and a log-and-leaf BUILDING never validates as one.
            // A missing property (a modded canopy) is assumed to decay — only proof demotes. The
            // eye still sees through leaves (sightClear below).
            boolean built = state.getOptionalValue(BlockStateProperties.PERSISTENT).orElse(false);
            // The decay rim (distance 7 — no log within reach) is a canopy DYING, usually one a
            // chop just orphaned. Counting it kept hypothesizing "trees" out of vanishing remnants
            // and fed dying cells into blobs and fishing menus (decision: Luiz, 2026-07-27).
            boolean dying = state.getOptionalValue(BlockStateProperties.DISTANCE).orElse(1) > 6;
            return built || dying ? BlockKind.OTHER : BlockKind.LEAVES;
        }
        // Open water only: a waterlogged fence is a fence to them, not something to drink from.
        if (state.getFluidState().is(FluidTags.WATER)
                && state.getCollisionShape(this.level, pos).isEmpty()) {
            return BlockKind.WATER;
        }
        return BlockKind.OTHER;
    }

    /**
     * Whether the ARM has a clear path from {@code from} to the target — stricter than seeing:
     * anything with a collision shape blocks a swing, INCLUDING leaves (eyes see through a canopy;
     * arms do not — caught live: a log broken through the leaves above it). Plants and water do not
     * impede an arm.
     */
    public static boolean armPathClear(Level level, Vec3 from, BlockPos target) {
        Vec3 to = Vec3.atCenterOf(target);
        int steps = (int) Math.ceil(from.distanceTo(to) * 2.0);
        for (int i = 1; i < steps; i++) {
            BlockPos cell = BlockPos.containing(from.lerp(to, i / (double) steps));
            if (cell.equals(target) || !level.isLoaded(cell)) {
                continue;
            }
            if (!level.getBlockState(cell).getCollisionShape(level, cell).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /**
     * A sampled march from the eyes to the target's center at half-block strides, transparent
     * through air, leaves and water (a tree's own canopy must not hide its trunk). Not exact voxel
     * traversal (a ray squeezing past a corner may miss the corner block), but it gates
     * <em>noticing</em>, not physics, and errs at most one block either way.
     */
    @Override
    public boolean visibleFromEyes(Pos target) {
        Vec3 to = new Vec3(target.x() + 0.5, target.y() + 0.5, target.z() + 0.5);
        return sightClear(this.level, this.eyes.getEyePosition(), to,
                new BlockPos(target.x(), target.y(), target.z()));
    }

    /**
     * Eye-to-BODY sight: rays from {@code from} to sampled points of the target's hitbox —
     * eyes, torso center, feet — cheapest-first with EARLY-OUT, so any visible body part
     * counts as seen (peeking over a wall works; decision: Luiz) and a fully visible person
     * costs a single march. Same eye transparency as {@link #visibleFromEyes}.
     */
    public static boolean bodyVisible(Level level, Vec3 from, LivingEntity target) {
        Vec3 feet = target.position();
        Vec3[] samples = {
                target.getEyePosition(),
                feet.add(0.0, target.getBbHeight() * 0.5, 0.0),
                feet.add(0.0, 0.1, 0.0),
        };
        for (Vec3 to : samples) {
            if (sightClear(level, from, to, BlockPos.containing(to))) {
                return true;
            }
        }
        return false;
    }

    /**
     * The cheap sight check — one ray, eye to body center. Enough for creatures and small bodies,
     * and much faster for farms and herds (decision: Luiz); persons keep the multi-sample
     * {@link #bodyVisible} so peeking over a wall still works on people.
     */
    public static boolean centerVisible(Level level, Vec3 from, LivingEntity target) {
        Vec3 center = target.position().add(0.0, target.getBbHeight() * 0.5, 0.0);
        return sightClear(level, from, center, BlockPos.containing(center));
    }

    /** The shared sight march — see {@link #visibleFromEyes} for the transparency rationale. */
    private static boolean sightClear(Level level, Vec3 from, Vec3 to, BlockPos targetCell) {
        int steps = (int) Math.ceil(from.distanceTo(to) * 2.0);
        for (int i = 1; i < steps; i++) {
            BlockPos cell = BlockPos.containing(from.lerp(to, i / (double) steps));
            if (cell.equals(targetCell) || !level.isLoaded(cell)) {
                continue;
            }
            // The ray needs FINER transparency than the BlockKind vocabulary: anything without a
            // collision shape is see-through (a meadow must not blind them), as are leaves and
            // water.
            BlockState state = level.getBlockState(cell);
            boolean transparent = state.isAir()
                    || state.is(BlockTags.LEAVES)
                    || state.getFluidState().is(FluidTags.WATER)
                    || state.getCollisionShape(level, cell).isEmpty();
            if (!transparent) {
                return false;
            }
        }
        return true;
    }
}
