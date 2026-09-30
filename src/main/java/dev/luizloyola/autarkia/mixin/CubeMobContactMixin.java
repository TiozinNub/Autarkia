package dev.luizloyola.autarkia.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.luizloyola.autarkia.mod.entity.Person;
//? if >=26.2 {
/*import net.minecraft.world.entity.monster.cubemob.AbstractCubeMob;
*///?} else {
import net.minecraft.world.entity.monster.Slime;
//?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A slime or magma cube hurts a Person it touches. Vanilla's contact damage reaches a player
 * ({@code playerTouch}) and an iron golem ({@code push}); this lets a Person through the golem's
 * check, so the cube's own gate — not tiny, not a sulfur cube — still decides.
 */
//? if >=26.2 {
/*@Mixin(AbstractCubeMob.class)
*///?} else {
@Mixin(Slime.class)
//?}
public abstract class CubeMobContactMixin {

    @WrapOperation(method = "push(Lnet/minecraft/world/entity/Entity;)V",
            at = @At(value = "CONSTANT", args = "classValue=net/minecraft/world/entity/animal/golem/IronGolem"))
    private boolean autarkia$aPersonToo(Object entity, Operation<Boolean> original) {
        return original.call(entity) || entity instanceof Person;
    }
}
