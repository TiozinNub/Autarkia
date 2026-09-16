package dev.luizloyola.autarkia.compat.client.anim;

import dev.luizloyola.autarkia.mixin.LivingEntitySwingAccess;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.world.entity.LivingEntity;

/**
 * The swing of one body on a stand-in that must hold its arm exactly where the body does. Before
 * 26.3 the swing is five public fields, copied every frame; from 26.3 it is one {@code SwingState}
 * the body advances in {@code baseTick}, so the stand-in is handed the body's own object once and
 * reads its progress from then on. A stand-in that never ticks cannot double-advance what it
 * shares.
 */
@Environment(EnvType.CLIENT)
public final class SwingMirror {

    private SwingMirror() {
    }

    /** Once, when the stand-in is made. */
    public static void bind(LivingEntity standIn, LivingEntity body) {
        //? if >=26.3 {
        /*((LivingEntitySwingAccess) standIn).autarkia$swingState(
                ((LivingEntitySwingAccess) body).autarkia$swingState());
        *///?}
    }

    /** Every frame, on the nodes where the swing is fields rather than a shared object. */
    public static void copy(LivingEntity standIn, LivingEntity body) {
        //? if <26.3 {
        standIn.swinging = body.swinging;
        standIn.swingTime = body.swingTime;
        standIn.swingingArm = body.swingingArm;
        standIn.attackAnim = body.attackAnim;
        standIn.oAttackAnim = body.oAttackAnim;
        //?}
    }
}
