package dev.luizloyola.autarkia.mixin;

import dev.luizloyola.autarkia.mod.brain.BeingKnocks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mining-in-progress makes no game event — vanilla's vibration bus only hears the final
 * {@code BLOCK_DESTROY}, so a sculk sensor (and therefore a Person's ear) is deaf to the
 * knocking of a pick against stone (caught live: Luiz mining behind a Person, unheard). The
 * crack-progress broadcast is the one choke point every miner passes through — the survival
 * player's {@code ServerPlayerGameMode} and our own {@code PersonBlockBreaker} both call it,
 * once per crack stage — so it doubles as the knock sound for nearby Persons' ears.
 */
@Mixin(ServerLevel.class)
abstract class ServerLevelCrackMixin {

    @Inject(method = "destroyBlockProgress", at = @At("HEAD"))
    private void autarkia$hearTheKnock(int breakerId, BlockPos pos, int progress, CallbackInfo ci) {
        if (progress >= 0) {
            BeingKnocks.onCrack((ServerLevel) (Object) this, breakerId, pos);
        }
    }
}
