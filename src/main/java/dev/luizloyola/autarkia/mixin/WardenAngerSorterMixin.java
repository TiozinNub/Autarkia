package dev.luizloyola.autarkia.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.luizloyola.autarkia.mod.entity.Person;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Among suspects equally angering, a warden ranks a Person with the players ahead of mobs. */
@Mixin(targets = "net.minecraft.world.entity.monster.warden.AngerManagement$Sorter")
public abstract class WardenAngerSorterMixin {

    @WrapOperation(method = "compare(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/entity/Entity;)I",
            at = @At(value = "CONSTANT", args = "classValue=net/minecraft/world/entity/player/Player"))
    private boolean autarkia$aPersonToo(Object entity, Operation<Boolean> original) {
        return original.call(entity) || entity instanceof Person;
    }
}
