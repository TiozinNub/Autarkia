package dev.luizloyola.autarkia.compat.sense;

import dev.luizloyola.autarkia.core.board.Plants;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The ground's plants by vanilla tag (docs/superpowers/specs/2026-10-01-home-area-design.md,
 * <i>Clearing</i>): what a tree may grow through, flowers and saplings — not leaves, which felling
 * owns, and nothing holding water, which is not a plant to pull up.
 */
public final class PlantBlocks {

    // By id, not BlockTags' constants: 26.2 moved saplings, flowers and leaves to a shared
    // block-and-item class, while the tag files themselves stayed where they were.
    private static final TagKey<Block> GROWS_THROUGH = tag("replaceable_by_trees");
    private static final TagKey<Block> FLOWERS = tag("flowers");
    private static final TagKey<Block> SAPLINGS = tag("saplings");
    private static final TagKey<Block> LEAVES = tag("leaves");

    private static final Map<String, Boolean> KNOWN = new ConcurrentHashMap<>();

    private static TagKey<Block> tag(String path) {
        return TagKey.create(Registries.BLOCK, Identifier.withDefaultNamespace(path));
    }

    private PlantBlocks() {
    }

    /** Call during mod initialization. Tags are read on first ask, once they are bound. */
    public static void register() {
        Plants.install(id -> KNOWN.computeIfAbsent(id, PlantBlocks::plant));
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, manager, success) -> KNOWN.clear());
    }

    private static boolean plant(String id) {
        Identifier key = Identifier.tryParse(id);
        Optional<Block> block = key == null ? Optional.empty() : BuiltInRegistries.BLOCK.getOptional(key);
        if (block.isEmpty()) {
            return false;
        }
        BlockState state = block.get().defaultBlockState();
        return (state.is(GROWS_THROUGH) || state.is(FLOWERS) || state.is(SAPLINGS))
                && !state.is(LEAVES) && state.getFluidState().isEmpty();
    }
}
