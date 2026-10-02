package dev.luizloyola.autarkia.compat.inv;

import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import org.jspecify.annotations.Nullable;

/**
 * What a store holds, read with nobody's hands — a party reading its own chests, which a Direction
 * may do and a body has to walk to.
 */
public final class StoreContents {

    private StoreContents() {
    }

    /**
     * What one store held when read: how many items the predicate accepted, the hunger points
     * those items are worth eaten, and empty slots.
     */
    public record Reading(int matching, int free, int nutrition) {
    }

    /**
     * How many items at this store the predicate accepts, and how many slots are empty: zeroes when
     * nothing stands there any more, empty when its chunk is not loaded — never a load, since a read
     * must not keep the world awake.
     *
     * @param counted cells already read this pass. A double chest can be claimed once per half, and
     *                both halves answer with all 54 slots, so the first read marks both.
     */
    public static Optional<Reading> read(ServerLevel level, Pos at, Predicate<String> ids,
                                         Set<BlockPos> counted) {
        BlockPos pos = new BlockPos(at.x(), at.y(), at.z());
        if (!level.isLoaded(pos)) {
            return Optional.empty();
        }
        if (!counted.add(pos)) {
            return Optional.of(new Reading(0, 0, 0));
        }
        Container container = container(level, pos, counted);
        if (container == null) {
            return Optional.of(new Reading(0, 0, 0));
        }
        int matching = 0;
        int free = 0;
        int nutrition = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack held = container.getItem(slot);
            if (held.isEmpty()) {
                free++;
            } else if (ids.test(BuiltInRegistries.ITEM.getKey(held.getItem()).toString())) {
                matching += held.getCount();
                FoodProperties food = held.get(DataComponents.FOOD);
                if (food != null) {
                    nutrition += food.nutrition() * held.getCount();
                }
            }
        }
        return Optional.of(new Reading(matching, free, nutrition));
    }

    /**
     * Adds what this store holds to {@code into}, item id to count, read as {@link #read} reads.
     *
     * @return false when its chunk is not loaded
     */
    public static boolean tally(ServerLevel level, Pos at, Set<BlockPos> counted, Map<String, Integer> into) {
        BlockPos pos = new BlockPos(at.x(), at.y(), at.z());
        if (!level.isLoaded(pos)) {
            return false;
        }
        if (!counted.add(pos)) {
            return true;
        }
        Container container = container(level, pos, counted);
        if (container != null) {
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack held = container.getItem(slot);
                if (!held.isEmpty()) {
                    into.merge(BuiltInRegistries.ITEM.getKey(held.getItem()).toString(), held.getCount(),
                            Integer::sum);
                }
            }
        }
        return true;
    }

    private static @Nullable Container container(ServerLevel level, BlockPos pos, Set<BlockPos> counted) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof ChestBlock chest) {
            if (state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
                counted.add(ChestBlock.getConnectedBlockPos(pos, state));
            }
            // `true`: a cat on the lid shuts the chest to a hand, not to a count.
            return ChestBlock.getContainer(chest, state, level, pos, true);
        }
        return level.getBlockEntity(pos) instanceof Container found ? found : null;
    }
}
