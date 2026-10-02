package dev.luizloyola.autarkia.compat.sense;

import dev.luizloyola.autarkia.core.earthwork.DigDirt;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;

/**
 * Ground that digs out as dirt, by vanilla tag: {@code #minecraft:dirt}, and the grass blocks
 * 26.1 took out of it into {@code #minecraft:grass_blocks}. Read by item tag, a grass-topped
 * column was never dug there.
 */
public final class DirtGround {

    private static final TagKey<Block> DIRT = tag("dirt");
    /** Absent before 26.1, where {@link #DIRT} still holds grass: asking an unbound tag is no. */
    private static final TagKey<Block> GRASS_BLOCKS = tag("grass_blocks");

    private static final Map<String, Boolean> KNOWN = new ConcurrentHashMap<>();

    private static TagKey<Block> tag(String path) {
        return TagKey.create(Registries.BLOCK, Identifier.withDefaultNamespace(path));
    }

    private DirtGround() {
    }

    /** Call during mod initialization. Tags are read on first ask, once they are bound. */
    public static void register() {
        DigDirt.groundBy(id -> KNOWN.computeIfAbsent(id, DirtGround::dirt));
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, manager, success) -> KNOWN.clear());
    }

    private static boolean dirt(String id) {
        Identifier key = Identifier.tryParse(id);
        Optional<Block> block = key == null ? Optional.empty() : BuiltInRegistries.BLOCK.getOptional(key);
        return block.isPresent()
                && (block.get().defaultBlockState().is(DIRT) || block.get().defaultBlockState().is(GRASS_BLOCKS));
    }
}
