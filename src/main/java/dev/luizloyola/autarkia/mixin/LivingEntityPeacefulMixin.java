package dev.luizloyola.autarkia.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.luizloyola.autarkia.mod.entity.Person;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * On Peaceful nothing may attack a Person, as nothing may attack a player: the piglins, hoglins and
 * bosses that stay on Peaceful would otherwise hunt Persons alone.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityPeacefulMixin {

    @WrapOperation(method = "canAttack(Lnet/minecraft/world/entity/LivingEntity;)Z",
            at = @At(value = "CONSTANT", args = "classValue=net/minecraft/world/entity/player/Player"))
    private boolean autarkia$aPersonToo(Object target, Operation<Boolean> original) {
        return original.call(target) || target instanceof Person;
    }
}
