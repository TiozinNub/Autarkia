package dev.luizloyola.autarkia.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.luizloyola.autarkia.compat.aggro.PersonAggro;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.monster.hoglin.Hoglin;
import net.minecraft.world.entity.monster.hoglin.HoglinAi;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Hoglins go for a Person as for a player: where their target finder reads the nearest visible
 * attackable player, the nearer of that player and a Person is read instead ({@link PersonAggro}).
 */
@Mixin(HoglinAi.class)
public abstract class HoglinAiMixin {

    @WrapOperation(method = "findNearestValidAttackTarget", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/ai/Brain;getMemory(Lnet/minecraft/world/entity/ai/memory/MemoryModuleType;)Ljava/util/Optional;"))
    private static Optional<?> autarkia$aPersonToo(Brain<?> brain, MemoryModuleType<?> type,
                                                   Operation<Optional<?>> original,
                                                   @Local(argsOnly = true) ServerLevel level,
                                                   @Local(argsOnly = true) Hoglin body) {
        Optional<?> found = original.call(brain, type);
        if (type == MemoryModuleType.NEAREST_VISIBLE_ATTACKABLE_PLAYER) {
            return PersonAggro.nearer(body, found, PersonAggro.nearestPerson(level, body, entity -> true));
        }
        return found;
    }
}
