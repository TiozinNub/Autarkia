package dev.luizloyola.autarkia.mixin;

import dev.luizloyola.autarkia.mod.brain.BeingVoices;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The hurt cry becomes hearable — the second voice point beside {@link MobAmbientSoundMixin}
 * ("idle, other sound like hurt or something: can know species" — decision: Luiz): a zombie's
 * hurt groan names ZOMBIE through a wall exactly like its idle groan does. Persons and players
 * ride the same rule — their "oof" says a person is there, never which one (voices name
 * species, not names). Soundless and silenced bodies are skipped.
 */
@Mixin(LivingEntity.class)
abstract class LivingEntityHurtSoundMixin {

    @Shadow
    protected abstract @Nullable SoundEvent getHurtSound(DamageSource source);

    @Inject(method = "playHurtSound", at = @At("HEAD"))
    private void autarkia$hurtVoiceCarries(DamageSource source, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (!self.level().isClientSide() && !self.isSilent()
                && this.getHurtSound(source) != null) {
            BeingVoices.voiced(self);
        }
    }
}
