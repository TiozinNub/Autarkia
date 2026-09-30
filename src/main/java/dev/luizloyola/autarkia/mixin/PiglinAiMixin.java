package dev.luizloyola.autarkia.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.luizloyola.autarkia.compat.aggro.PersonAggro;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.monster.piglin.PiglinAi;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Piglins go for a Person as for a player: one not wearing gold, and anyone once universally
 * angry. Where their target finder reads either player memory, the nearer of that player and a
 * Person who fits is read instead ({@link PersonAggro}).
 */
@Mixin(PiglinAi.class)
public abstract class PiglinAiMixin {

    @WrapOperation(method = "findNearestValidAttackTarget", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/ai/Brain;getMemory(Lnet/minecraft/world/entity/ai/memory/MemoryModuleType;)Ljava/util/Optional;"))
    private static Optional<?> autarkia$aPersonToo(Brain<?> brain, MemoryModuleType<?> type,
                                                   Operation<Optional<?>> original,
                                                   @Local(argsOnly = true) ServerLevel level,
                                                   @Local(argsOnly = true) Piglin body) {
        Optional<?> found = original.call(brain, type);
        if (type == MemoryModuleType.NEAREST_VISIBLE_ATTACKABLE_PLAYER) {
            return PersonAggro.nearer(body, found, PersonAggro.nearestPerson(level, body, entity -> true));
        }
        if (type == MemoryModuleType.NEAREST_TARGETABLE_PLAYER_NOT_WEARING_GOLD) {
            return PersonAggro.nearer(body, found, PersonAggro.nearestPerson(level, body,
                    entity -> !PiglinAi.isWearingSafeArmor(entity) && body.canAttack(entity)));
        }
        return found;
    }
}
