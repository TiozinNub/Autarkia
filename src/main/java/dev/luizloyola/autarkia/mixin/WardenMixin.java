package dev.luizloyola.autarkia.mixin;

import dev.luizloyola.autarkia.mod.entity.Person;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.monster.warden.AngerLevel;
import net.minecraft.world.entity.monster.warden.AngerManagement;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A warden angry enough at a Person drops a lesser target for them, as it does for a player: the
 * rule in {@code increaseAngerAt}, applied after vanilla's for a Person.
 */
@Mixin(Warden.class)
public abstract class WardenMixin {

    @Shadow private AngerManagement angerManagement;

    /** Whether the target before the anger rose was a player or a Person, whom it would keep. */
    @Unique private boolean autarkia$huntingSomeone;

    @Inject(method = "increaseAngerAt(Lnet/minecraft/world/entity/Entity;IZ)V", at = @At("HEAD"))
    private void autarkia$before(Entity entity, int amount, boolean playSound, CallbackInfo ci) {
        LivingEntity target = ((Warden) (Object) this).getTarget();
        autarkia$huntingSomeone = target instanceof Player || target instanceof Person;
    }

    @Inject(method = "increaseAngerAt(Lnet/minecraft/world/entity/Entity;IZ)V", at = @At("TAIL"))
    private void autarkia$after(Entity entity, int amount, boolean playSound, CallbackInfo ci) {
        Warden self = (Warden) (Object) this;
        if (entity instanceof Person && !autarkia$huntingSomeone && !self.isNoAi() && self.canTargetEntity(entity)
                && AngerLevel.byAnger(((AngerManagementAccessor) angerManagement)
                        .autarkia$angerBySuspect().getOrDefault(entity, 0)).isAngry()) {
            self.getBrain().eraseMemory(MemoryModuleType.ATTACK_TARGET);
        }
    }
}
