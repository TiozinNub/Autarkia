package dev.luizloyola.autarkia.mixin;

import dev.luizloyola.autarkia.mod.entity.Person;
import net.minecraft.world.entity.monster.breeze.Breeze;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
//? if >=26.1 {
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.LivingEntity;
//?} else {
/*import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.luizloyola.autarkia.mod.entity.ModEntities;
import net.minecraft.world.entity.EntityType;
*///?}

/** A breeze attacks a Person as it attacks a player; vanilla lets it attack players and golems only. */
@Mixin(Breeze.class)
public abstract class BreezeMixin {

    //? if >=26.1 {
    @WrapOperation(method = "canAttack", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/LivingEntity;is(Ljava/lang/Object;)Z", ordinal = 0))
    private boolean autarkia$aPersonToo(LivingEntity target, Object type, Operation<Boolean> original) {
        return original.call(target, type) || target instanceof Person;
    }
    //?} else {
    /*@ModifyReturnValue(method = "canAttackType", at = @At("RETURN"))
    private boolean autarkia$aPersonToo(boolean original, @Local(argsOnly = true) EntityType<?> type) {
        return original || type == ModEntities.PERSON;
    }
    *///?}
}
