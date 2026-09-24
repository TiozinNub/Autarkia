package dev.luizloyola.autarkia.compat.inv;

import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;

/**
 * What a store holds, read with nobody's hands — a party reading its own chests, which a Direction
 * may do and a body has to walk to.
 */
public final class StoreContents {

    private StoreContents() {
    }

    /**
     * How many items at this store the predicate accepts: zero when nothing stands there any more,
     * empty when its chunk is not loaded — never a load, since a read must not keep the world awake.
     *
     * @param counted cells already read this pass. A double chest can be claimed once per half, and
     *                both halves answer with all 54 slots, so the first read marks both.
     */
    public static OptionalInt count(ServerLevel level, Pos at, Predicate<String> ids,
                                    Set<BlockPos> counted) {
        BlockPos pos = new BlockPos(at.x(), at.y(), at.z());
        if (!level.isLoaded(pos)) {
            return OptionalInt.empty();
        }
        if (!counted.add(pos)) {
            return OptionalInt.of(0);
        }
        BlockState state = level.getBlockState(pos);
        Container container;
        if (state.getBlock() instanceof ChestBlock chest) {
            if (state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
                counted.add(ChestBlock.getConnectedBlockPos(pos, state));
            }
            // `true`: a cat on the lid shuts the chest to a hand, not to a count.
            container = ChestBlock.getContainer(chest, state, level, pos, true);
        } else {
            container = level.getBlockEntity(pos) instanceof Container found ? found : null;
        }
        if (container == null) {
            return OptionalInt.of(0);
        }
        int total = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack held = container.getItem(slot);
            if (!held.isEmpty() && ids.test(BuiltInRegistries.ITEM.getKey(held.getItem()).toString())) {
                total += held.getCount();
            }
        }
        return OptionalInt.of(total);
    }
}
