package dev.luizloyola.autarkia.mixin;

import dev.luizloyola.autarkia.mod.brain.PlaceMarks;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The game-event choke point: every world happening with a source rides through here, so it
 * is the one place to stamp perception marks server-wide (radius-free — unlike a Person's
 * ear, which only covers its own hearing bubble). Today it feeds {@link PlaceMarks} from
 * BLOCK_PLACE. That is what lets a WATCHING peer classify the placing swing as building.
 */
@Mixin(ServerLevel.class)
abstract class ServerLevelGameEventMixin {

    @Inject(method = "gameEvent(Lnet/minecraft/core/Holder;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/level/gameevent/GameEvent$Context;)V",
            at = @At("HEAD"))
    private void autarkia$perceptionMarks(Holder<GameEvent> event, Vec3 pos,
                                          GameEvent.Context context, CallbackInfo ci) {
        if (event.is(GameEvent.BLOCK_PLACE)
                && context.sourceEntity() instanceof LivingEntity body) {
            PlaceMarks.mark(body.getUUID(), ((ServerLevel) (Object) this).getGameTime());
        }
    }
}
