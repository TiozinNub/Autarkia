package dev.luizloyola.autarkia.compat.bp;

import dev.luizloyola.anima.compat.terrain.GroundReader;
import dev.luizloyola.autarkia.core.bp.Blueprint.Outcome;
import dev.luizloyola.autarkia.core.bp.BuildPlan;
import dev.luizloyola.autarkia.core.bp.Placement;
import dev.luizloyola.autarkia.core.bp.Support;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.SupportType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.jspecify.annotations.Nullable;

/**
 * Writes a {@link BuildPlan} into a level at once — {@code bp place}, for authoring. The builder
 * will place block by block instead. Every block turns by vanilla's own {@code mirror} then
 * {@code rotate}, in the order {@code StructureTemplate} uses; {@code ?} is left alone and
 * {@code ~} filled from the ground under it.
 */
public final class Placer {

    /** Indexed by {@link Placement#turns()}. */
    private static final Rotation[] TURNS = {Rotation.NONE, Rotation.CLOCKWISE_90, Rotation.CLOCKWISE_180,
            Rotation.COUNTERCLOCKWISE_90};

    /** Indexed by {@link Support.Face}'s ordinal. */
    private static final Direction[] FACES = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST,
            Direction.UP, Direction.DOWN};

    /** Vanilla's own test, so a fence post holds a torch on top and not on its side. */
    public static final Support SUPPORT = (block, face, center) -> resolve(block)
            .map(state -> state.isFaceSturdy(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, FACES[face.ordinal()],
                    center ? SupportType.CENTER : SupportType.FULL))
            .orElse(false);

    /** As deep as a column is read for the ground a {@code ~} copies; past it, dirt. */
    private static final int GROUND_SEARCH = 48;

    public enum Status { PLACED, UNLOADED, OUTSIDE_WORLD, UNKNOWN_STATE }

    /**
     * @param detail for {@link Status#UNKNOWN_STATE}, the block that did not resolve
     */
    public record Result(Status status, int blocks, int cleared, int filled, String detail) {
        static Result refused(Status status, String detail) {
            return new Result(status, 0, 0, 0, detail);
        }
    }

    private record Write(BlockPos pos, BlockState state) {
    }

    private Placer() {
    }

    /** Nothing is written unless all of it can be: every chunk loaded, every state known. */
    public static Result place(ServerLevel level, BlockPos anchor, BuildPlan plan, Placement placement) {
        int width = placement.width(plan.width(), plan.depth());
        int depth = placement.depth(plan.width(), plan.depth());
        int minX = anchor.getX() + Placement.offset(0, width);
        int maxX = anchor.getX() + Placement.offset(width - 1, width);
        int minZ = anchor.getZ() + Placement.offset(0, depth);
        int maxZ = anchor.getZ() + Placement.offset(depth - 1, depth);
        int minY = anchor.getY() + plan.minLayer();
        int maxY = anchor.getY() + plan.maxLayer();
        if (level.isOutsideBuildHeight(minY) || level.isOutsideBuildHeight(maxY)) {
            return Result.refused(Status.OUTSIDE_WORLD, minY + ".." + maxY);
        }
        for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
            for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
                if (!level.hasChunk(cx, cz)) {
                    return Result.refused(Status.UNLOADED, cx + "," + cz);
                }
            }
        }
        Mirror mirror = placement.flip() ? Mirror.FRONT_BACK : Mirror.NONE;
        Rotation rotation = TURNS[placement.turns()];
        Map<Outcome, Optional<BlockState>> resolved = new HashMap<>();
        List<Write> writes = new ArrayList<>();
        int[] counts = new int[3];
        String[] unknown = {null};
        // Everything read before anything is written, so a ~ copies the ground as it was.
        plan.forEach(placement, (dx, layer, dz, kind, state) -> {
            BlockPos pos = anchor.offset(dx, layer, dz);
            switch (kind) {
                case KEEP -> {
                }
                case AIR -> {
                    writes.add(new Write(pos, Blocks.AIR.defaultBlockState()));
                    counts[1]++;
                }
                case TERRAIN -> {
                    if (!isGround(level, pos, level.getBlockState(pos))) {
                        writes.add(new Write(pos, groundUnder(level, pos)));
                        counts[2]++;
                    }
                }
                case BLOCK -> {
                    Optional<BlockState> block = resolved.computeIfAbsent(state, Placer::resolve);
                    if (block.isEmpty()) {
                        unknown[0] = state.block() + state.props();
                    } else {
                        writes.add(new Write(pos, block.get().mirror(mirror).rotate(rotation)));
                        counts[0]++;
                    }
                }
            }
        });
        if (unknown[0] != null) {
            return Result.refused(Status.UNKNOWN_STATE, unknown[0]);
        }
        for (Write write : writes) {
            level.setBlock(write.pos(), write.state(), Block.UPDATE_CLIENTS);
        }
        // StructureTemplate's second pass: fences join, stairs corner, and neighbours hear of it.
        for (Write write : writes) {
            BlockState was = level.getBlockState(write.pos());
            BlockState shaped = Block.updateFromNeighbourShapes(was, level, write.pos());
            if (shaped != was) {
                level.setBlock(write.pos(), shaped, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            }
            level.updateNeighborsAt(write.pos(), shaped.getBlock());
        }
        return new Result(Status.PLACED, counts[0], counts[1], counts[2], "");
    }

    private static Optional<BlockState> resolve(Outcome outcome) {
        Identifier id = Identifier.tryParse(outcome.block());
        Optional<Block> block = id == null ? Optional.empty() : BuiltInRegistries.BLOCK.getOptional(id);
        if (block.isEmpty()) {
            return Optional.empty();
        }
        BlockState state = block.get().defaultBlockState();
        for (Map.Entry<String, String> prop : outcome.props().entrySet()) {
            Property<?> property = block.get().getStateDefinition().getProperty(prop.getKey());
            state = property == null ? null : with(state, property, prop.getValue());
            if (state == null) {
                return Optional.empty();
            }
        }
        return Optional.of(state);
    }

    private static <T extends Comparable<T>> @Nullable BlockState with(BlockState state, Property<T> property,
                                                                       String value) {
        return property.getValue(value).map(v -> state.setValue(property, v)).orElse(null);
    }

    /**
     * Solid ground already, so a {@code ~} leaves it: a full block, dry, and not a trunk, a canopy
     * or something somebody put there.
     */
    private static boolean isGround(ServerLevel level, BlockPos pos, BlockState state) {
        return state.isCollisionShapeFullBlock(level, pos) && state.getFluidState().isEmpty()
                && !state.is(BlockTags.LEAVES) && !state.is(GroundReader.NOT_GROUND)
                && !state.is(GroundReader.USED_GROUND);
    }

    /** The first ground down the column — the hill's own dirt, the lake's own sand — else dirt. */
    private static BlockState groundUnder(ServerLevel level, BlockPos pos) {
        BlockPos.MutableBlockPos at = pos.mutable();
        for (int i = 0; i < GROUND_SEARCH && !level.isOutsideBuildHeight(at.getY() - 1); i++) {
            at.move(0, -1, 0);
            BlockState state = level.getBlockState(at);
            if (isGround(level, at, state)) {
                return state;
            }
        }
        return Blocks.DIRT.defaultBlockState();
    }
}
