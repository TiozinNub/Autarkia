package dev.luizloyola.autarkia.mixin;

import dev.luizloyola.anima.mod.brain.BeingVoices;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.Mob;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The idle call becomes hearable: {@code playAmbientSound} is vanilla's one plain-sound "this mob
 * called" point (no game event, verified in 26.1.2 bytecode), so it emits
 * {@code anima:being_voice} for Persons' ears. A voice names its species — the identification
 * ladder's middle rung (decision: Luiz). Silenced mobs and species with no ambient sound (squid)
 * are skipped: a voice that made no sound identifies nothing.
 */
@Mixin(Mob.class)
abstract class MobAmbientSoundMixin {

    @Shadow
    protected abstract @Nullable SoundEvent getAmbientSound();

    @Inject(method = "playAmbientSound", at = @At("HEAD"))
    private void autarkia$voiceCarries(CallbackInfo ci) {
        Mob self = (Mob) (Object) this;
        if (!self.level().isClientSide() && !self.isSilent() && this.getAmbientSound() != null) {
            BeingVoices.voiced(self);
        }
    }
}
