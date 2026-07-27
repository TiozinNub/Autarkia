package dev.luizloyola.autarkia.mod.client.anim;

import dev.luizloyola.autarkia.mod.AutarkiaMod;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.LivingEntity;

/**
 * A one-line, entirely optional bridge into <a
 * href="https://github.com/tr7zw/NotEnoughAnimations">NotEnoughAnimations</a>, so a Person animates
 * with the same animations NEA gives players.
 *
 * <p>NEA already runs on our Persons — its {@code LivingEntityRendererMixin} records the entity on
 * the render state at the HEAD of {@code LivingEntityRenderer.extractRenderState}, which
 * {@code PersonRenderer} calls through {@code super} — but declines them: it is typed to
 * {@code AbstractClientPlayer} all the way down, and that type is in the METHOD DESCRIPTORS, which
 * no mixin widens, since Mixin rewrites method bodies, not signatures. So this swaps the one value
 * NEA keys off: right after extraction records the Person, it is replaced with the Person's
 * {@link ShadowPlayer}, a real, inert {@code AbstractClientPlayer}.
 *
 * <p><b>Soft in every direction.</b> Nothing is compiled against NEA; the surface is one reflective
 * handle on {@code ExtendedLivingRenderState.setEntity}, resolved once. NEA absent, an unexpected
 * version, a renamed interface or a throw from inside disables the bridge permanently after one
 * warning, and the Person falls back to vanilla arm poses. Treat a break as expected wear.
 */
@Environment(EnvType.CLIENT)
public final class NeaBridge {
    private static final String MOD_ID = "notenoughanimations";
    private static final String STATE_INTERFACE =
            "dev.tr7zw.notenoughanimations.access.ExtendedLivingRenderState";

    /** {@code ExtendedLivingRenderState.setEntity(LivingEntity)}, or null when the bridge is off. */
    private static MethodHandle setEntity;
    private static boolean resolved;
    private static boolean disabled;

    private NeaBridge() {
    }

    /**
     * Whether NEA is present and its render-state hook was reachable — checked before a {@link
     * ShadowPlayer} is ever built, so a client without the mod pays nothing but this boolean.
     */
    public static boolean available() {
        if (!resolved) {
            resolve();
        }
        return !disabled;
    }

    /**
     * Points NEA at {@code shadow} for this render state, replacing the Person its own extraction
     * hook just recorded. Must run after {@code super.extractRenderState}, which is where NEA writes
     * the original — otherwise this is immediately overwritten.
     */
    public static void retarget(Object renderState, AbstractClientPlayer shadow) {
        if (!available()) return;
        try {
            setEntity.invoke(renderState, (LivingEntity) shadow);
        } catch (Throwable t) {
            disable("setEntity threw", t);
        }
    }

    private static void resolve() {
        resolved = true;
        if (!FabricLoader.getInstance().isModLoaded(MOD_ID)) {
            disabled = true;
            // Said out loud on purpose: a silent branch reads exactly like "the bridge never ran"
            // when you are reading a log.
            AutarkiaMod.LOGGER.info(
                    "NotEnoughAnimations not installed — Persons use vanilla arm poses.");
            return;
        }
        try {
            Class<?> stateInterface = Class.forName(STATE_INTERFACE);
            setEntity = MethodHandles.lookup()
                    .unreflect(stateInterface.getMethod("setEntity", LivingEntity.class));
            AutarkiaMod.LOGGER.info(
                    "NotEnoughAnimations detected — Persons will animate through it.");
        } catch (Throwable t) {
            disable("could not bind NotEnoughAnimations", t);
        }
    }

    /** Off for the rest of the session — one warning, then silence, then vanilla poses. */
    private static void disable(String why, Throwable t) {
        disabled = true;
        setEntity = null;
        AutarkiaMod.LOGGER.warn(
                "NotEnoughAnimations bridge disabled ({}); Persons fall back to vanilla arm poses.",
                why, t);
    }
}
