package dev.luizloyola.autarkia.mixin;

import dev.luizloyola.autarkia.compat.aggro.PhantomHuntsPersons;
import net.minecraft.world.entity.monster.Phantom;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A phantom hunts Persons as it hunts players. Its own hunt takes a {@code List<Player>}, so a
 * second goal at the same priority scans both and takes a Person when one is the pick.
 */
@Mixin(Phantom.class)
public abstract class PhantomGoalsMixin {

    @Inject(method = "registerGoals", at = @At("TAIL"))
    private void autarkia$huntPersonsToo(CallbackInfo ci) {
        Phantom self = (Phantom) (Object) this;
        ((MobTargetSelectorAccessor) self).autarkia$targetSelector().addGoal(1, new PhantomHuntsPersons(self));
    }
}
