package dev.luizloyola.autarkia.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.luizloyola.autarkia.mod.entity.Person;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * On Peaceful nothing may attack a Person, as nothing may attack a player: the piglins, hoglins and
 * bosses that stay on Peaceful would otherwise hunt Persons alone.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityPeacefulMixin {

    @ModifyReturnValue(method = "canAttack(Lnet/minecraft/world/entity/LivingEntity;)Z", at = @At("RETURN"))
    private boolean autarkia$sparedOnPeaceful(boolean original,
                                              @Local(argsOnly = true) LivingEntity target) {
        return original && !(target instanceof Person
                && ((LivingEntity) (Object) this).level().getDifficulty() == Difficulty.PEACEFUL);
    }
}
