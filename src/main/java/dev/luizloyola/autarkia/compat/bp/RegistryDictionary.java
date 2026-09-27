package dev.luizloyola.autarkia.compat.bp;

import dev.luizloyola.autarkia.compat.inv.ItemIds;
import dev.luizloyola.autarkia.core.bp.Dictionary.BlockInfo;
import dev.luizloyola.autarkia.core.bp.DictionaryRules;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The block registry as the blueprint dictionary's raw material: every block's id, its default
 * state's properties, and the physical facts the checks read. Only true once tags are bound — at
 * {@code SERVER_STARTED} and after a reload — because the nether woods are found by a tag.
 */
public final class RegistryDictionary {

    private RegistryDictionary() {
    }

    public static DictionaryRules.Derived build() {
        List<BlockInfo> blocks = new ArrayList<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            blocks.add(info(block));
        }
        // An item tag, not a block tag: vanilla only tags the planks' items as the woods that do not burn.
        Set<String> netherPlanks = ItemIds.inTag("minecraft:non_flammable_wood").orElse(Set.of());
        return DictionaryRules.derive(blocks, netherPlanks);
    }

    private static BlockInfo info(Block block) {
        BlockState state = block.defaultBlockState();
        Map<String, List<String>> properties = new LinkedHashMap<>();
        Map<String, String> defaults = new HashMap<>();
        for (Property<?> property : state.getProperties()) {
            List<String> values = new ArrayList<>();
            for (Object value : property.getPossibleValues()) {
                values.add(name(property, value));
            }
            properties.put(property.getName(), values);
            defaults.put(property.getName(), name(property, state.getValue(property)));
        }
        Item item = block.asItem();
        String itemId = item == Items.AIR ? "" : BuiltInRegistries.ITEM.getKey(item).toString();
        VoxelShape collision = state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        // Half a block or taller stops a body and the air; a ladder is collision you climb, not a wall.
        boolean obstructs = !collision.isEmpty() && collision.max(Direction.Axis.Y) >= 0.5
                && !state.is(BlockTags.CLIMBABLE);
        return new BlockInfo(BuiltInRegistries.BLOCK.getKey(block).toString(), properties, defaults,
                state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO), obstructs,
                state.getLightEmission(), block instanceof FallingBlock, itemId);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Comparable<T>> String name(Property<T> property, Object value) {
        return property.getName((T) value);
    }
}
