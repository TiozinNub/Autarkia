package dev.luizloyola.autarkia.compat.entity;

import dev.luizloyola.autarkia.mixin.LivingEntitySwingAccess;
import net.minecraft.world.entity.LivingEntity;

/**
 * The clock behind the visible arm swing, for a body vanilla does not wind. Before 26.3 only
 * {@code Player.aiStep} advanced it, so an {@code Avatar} that is not a Player broadcast its swing
 * and then animated nothing, frozen at frame zero. 26.3 moved the tick into
 * {@code LivingEntity.baseTick}, where every living body gets it, and this became a no-op.
 */
public final class SwingClock {

    private SwingClock() {
    }

    /** One tick of the swing, on the nodes where the body has to wind it itself. */
    public static void advance(LivingEntity body) {
        //? if <26.3 {
        ((LivingEntitySwingAccess) body).autarkia$updateSwingTime();
        //?}
    }
}
