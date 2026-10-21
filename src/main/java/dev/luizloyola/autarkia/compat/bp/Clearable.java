package dev.luizloyola.autarkia.compat.bp;

import java.util.Optional;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** The blocks a builder breaks out of a cell it builds in, by vanilla's tags. */
public final class Clearable {

    private Clearable() {
    }

    /**
     * Leaves, and what {@code #minecraft:replaceable} or {@code #minecraft:replaceable_by_trees}
     * names — grass, vines, a snow layer — that a hand can break; never air or a fluid.
     */
    public static boolean soft(String blockId) {
        Identifier id = Identifier.tryParse(blockId);
        Optional<Block> block = id == null ? Optional.empty() : BuiltInRegistries.BLOCK.getOptional(id);
        if (block.isEmpty() || block.get().defaultDestroyTime() < 0) {
            return false;
        }
        BlockState state = block.get().defaultBlockState();
        if (state.isAir() || !state.getFluidState().isEmpty()) {
            return false;
        }
        return state.is(BlockTags.LEAVES) || state.is(BlockTags.REPLACEABLE) || state.is(BlockTags.REPLACEABLE_BY_TREES);
    }
}
