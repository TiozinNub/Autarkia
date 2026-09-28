package dev.luizloyola.autarkia.compat.forage;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;

/**
 * Picking a sweet berry bush with an empty hand, as vanilla's own use does it: the harvest loot
 * table, the pick sound, the bush back to age 1 so it grows more. Vanilla's path wants a
 * {@code Player}; this is the same steps with the picker as the entity, so the loot table and the
 * vibration see a settler.
 */
public final class BerryPicking {

    private BerryPicking() {
    }

    /** Picks a ripe bush (age 2 or more) and returns true; anything else is left alone. */
    public static boolean pick(ServerLevel level, BlockPos pos, BlockState state, LivingEntity picker) {
        if (!state.is(Blocks.SWEET_BERRY_BUSH) || state.getValue(SweetBerryBushBlock.AGE) < 2) {
            return false;
        }
        LootParams params = new LootParams.Builder(level)
                .withParameter(LootContextParams.BLOCK_STATE, state)
                .withOptionalParameter(LootContextParams.BLOCK_ENTITY, level.getBlockEntity(pos))
                .withOptionalParameter(LootContextParams.INTERACTING_ENTITY, picker)
                .create(LootContextParamSets.BLOCK_INTERACT);
        level.getServer().reloadableRegistries()
                .getLootTable(BuiltInLootTables.HARVEST_SWEET_BERRY_BUSH)
                .getRandomItems(params, stack -> Block.popResource(level, pos, stack));
        level.playSound(null, pos, SoundEvents.SWEET_BERRY_BUSH_PICK_BERRIES, SoundSource.BLOCKS,
                1.0F, 0.8F + level.getRandom().nextFloat() * 0.4F);
        BlockState picked = state.setValue(SweetBerryBushBlock.AGE, 1);
        level.setBlock(pos, picked, Block.UPDATE_CLIENTS);
        level.gameEvent(GameEvent.BLOCK_CHANGE, pos, GameEvent.Context.of(picker, picked));
        return true;
    }
}
