package dev.luizloyola.autarkia.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.luizloyola.autarkia.mod.entity.Person;
import net.minecraft.world.entity.monster.warden.Warden;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** A warden angry enough at a Person drops a lesser target for them, as it does for a player. */
@Mixin(Warden.class)
public abstract class WardenMixin {

    @WrapOperation(method = "increaseAngerAt(Lnet/minecraft/world/entity/Entity;IZ)V",
            at = @At(value = "CONSTANT", args = "classValue=net/minecraft/world/entity/player/Player"))
    private boolean autarkia$aPersonToo(Object entity, Operation<Boolean> original) {
        return original.call(entity) || entity instanceof Person;
    }
}
