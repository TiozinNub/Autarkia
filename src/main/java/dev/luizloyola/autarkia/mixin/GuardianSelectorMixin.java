package dev.luizloyola.autarkia.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.luizloyola.autarkia.mod.entity.Person;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Guardian;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** A guardian targets a Person as it does a player: more than three blocks off. */
@Mixin(targets = "net.minecraft.world.entity.monster.Guardian$GuardianAttackSelector")
public abstract class GuardianSelectorMixin {

    @Shadow @Final private Guardian guardian;

    @ModifyReturnValue(method = "test", at = @At("RETURN"))
    private boolean autarkia$aPersonToo(boolean original, @Local(argsOnly = true) LivingEntity target) {
        return original || target instanceof Person && target.distanceToSqr(guardian) > 9.0;
    }
}
