package dev.luizloyola.autarkia.mixin;

import dev.luizloyola.autarkia.mod.entity.Person;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.sensing.Sensor;
import net.minecraft.world.entity.monster.creaking.Creaking;
import net.minecraft.world.entity.monster.creaking.CreakingAi;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A creaking keeps hunting a Person while it could have seen one: the same test vanilla's sensor
 * applies to a player before it counts as a visible, attackable one.
 */
@Mixin(CreakingAi.class)
public abstract class CreakingAiMixin {

    @Inject(method = "isAttackTargetStillReachable", at = @At("HEAD"), cancellable = true)
    private static void autarkia$aPerson(Creaking creaking, LivingEntity target,
                                        CallbackInfoReturnable<Boolean> cir) {
        if (target instanceof Person && creaking.level() instanceof ServerLevel level) {
            cir.setReturnValue(creaking.closerThan(target, creaking.getAttributeValue(Attributes.FOLLOW_RANGE))
                    && Sensor.isEntityTargetable(level, creaking, target)
                    && Sensor.isEntityAttackable(level, creaking, target));
        }
    }
}
