package dev.luizloyola.autarkia.mod.client.anim;

import com.mojang.authlib.GameProfile;
import dev.luizloyola.autarkia.mod.AutarkiaMod;
import dev.luizloyola.autarkia.mod.entity.Person;
import java.lang.reflect.Field;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

/**
 * One Person's stand-in player: an inert {@link RemotePlayer}, never in the world and never
 * rendered — the {@code AbstractClientPlayer} shape {@link NeaBridge} demands (see that class).
 *
 * <p>{@link #synced()} copies the animation-relevant slice of the Person onto it each frame; NEA
 * writes its results into the {@code PlayerModel}, which is the Person's own. Per-Person and
 * long-lived because NEA keeps per-entity state on it — a shared or per-frame shadow would smear
 * every Person's smoothing together.
 *
 * <p><b>Not mirrored:</b> body/head rotation lock (1.21.9+ retained pipeline: body yaw comes from
 * the render state, extracted before {@code setupAnim}, so NEA writing {@code yBodyRot} reaches
 * nothing) and water, fire and freezing (fields only a real tick updates). Pose, rotation, held
 * items and item use DO carry.
 */
@Environment(EnvType.CLIENT)
public final class ShadowPlayer {
    /** Short, fixed, and not "deadmau5" — {@code AbstractClientPlayer} reads that name
     *  to decide on ears, and a 16-char cap is the safe assumption for a profile name. */
    private static final String PROFILE_NAME = "autarkia";

    /** {@code LivingEntity.useItem} / {@code useItemRemaining}: protected, so reflected. They are
     *  the only way to hand NEA a countdown — {@code startUsingItem} would re-arm the timer to full
     *  duration on every call (client-side it never sets the using flag that guards re-entry), and a
     *  frozen counter means an eating arm raised but perfectly still. Same field names on 1.21.11,
     *  26.1.x and 26.2.x. */
    private static @Nullable Field useItemField;
    private static @Nullable Field useItemRemainingField;
    private static boolean fieldsResolved;

    private final Person person;
    private @Nullable RemotePlayer shadow;

    public ShadowPlayer(Person person) {
        this.person = person;
    }

    /**
     * Mirrors the Person onto the shadow and hands it over, or null when there is no shadow to be
     * had (no client level yet, or the reflected use-item fields never bound). Callers treat null as
     * "no NEA this frame" and render vanilla.
     */
    public @Nullable AbstractClientPlayer synced() {
        RemotePlayer target = shadow();
        if (target == null) return null;
        Person source = this.person;

        target.setPos(source.getX(), source.getY(), source.getZ());
        target.xo = source.xo;
        target.yo = source.yo;
        target.zo = source.zo;
        target.setYRot(source.getYRot());
        target.yRotO = source.yRotO;
        target.setXRot(source.getXRot());
        target.xRotO = source.xRotO;
        target.yHeadRot = source.yHeadRot;
        target.yHeadRotO = source.yHeadRotO;
        target.yBodyRot = source.yBodyRot;
        target.yBodyRotO = source.yBodyRotO;
        target.setDeltaMovement(source.getDeltaMovement());
        target.setOnGround(source.onGround());
        target.setPose(source.getPose());
        target.setShiftKeyDown(source.isShiftKeyDown());
        target.setSprinting(source.isSprinting());
        target.tickCount = source.tickCount;

        target.setItemSlot(EquipmentSlot.MAINHAND, source.getMainHandItem());
        target.setItemSlot(EquipmentSlot.OFFHAND, source.getOffhandItem());
        target.swinging = source.swinging;
        target.swingTime = source.swingTime;
        target.swingingArm = source.swingingArm;
        target.attackAnim = source.attackAnim;
        target.oAttackAnim = source.oAttackAnim;
        try {
            useItemField.set(target, source.getUseItem());
            useItemRemainingField.setInt(target, source.getUseItemRemainingTicks());
        } catch (Throwable t) {
            // Bound once and never expected to fail after; if it does, stop pretending.
            useItemField = null;
            useItemRemainingField = null;
            this.shadow = null;
            AutarkiaMod.LOGGER.warn("Shadow player use-item mirror failed; dropping the shadow.", t);
            return null;
        }
        return target;
    }

    /** The shadow, built on first use against the Person's own client level. */
    private @Nullable RemotePlayer shadow() {
        if (this.shadow != null) return this.shadow;
        if (!resolveFields()) return null;
        Level level = this.person.level();
        if (!(level instanceof ClientLevel clientLevel)) return null;
        this.shadow = new RemotePlayer(
                clientLevel, new GameProfile(this.person.getUUID(), PROFILE_NAME));
        return this.shadow;
    }

    private static boolean resolveFields() {
        if (!fieldsResolved) {
            fieldsResolved = true;
            try {
                useItemField = LivingEntity.class.getDeclaredField("useItem");
                useItemField.setAccessible(true);
                useItemRemainingField = LivingEntity.class.getDeclaredField("useItemRemaining");
                useItemRemainingField.setAccessible(true);
            } catch (Throwable t) {
                useItemField = null;
                useItemRemainingField = null;
                AutarkiaMod.LOGGER.warn(
                        "Could not reach LivingEntity.useItem/useItemRemaining; "
                                + "Persons keep vanilla arm poses.", t);
            }
        }
        return useItemField != null && useItemRemainingField != null;
    }
}
