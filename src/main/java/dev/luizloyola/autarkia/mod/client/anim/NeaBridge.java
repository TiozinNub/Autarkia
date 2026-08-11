package dev.luizloyola.autarkia.mod.client.anim;

import dev.luizloyola.autarkia.mod.AutarkiaMod;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.function.Supplier;
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
    private static final String PLAYER_DATA = "dev.tr7zw.notenoughanimations.access.PlayerData";
    private static final String DATA_HOLDER =
            "dev.tr7zw.notenoughanimations.versionless.animations.DataHolder";
    private static final String STATE_HOLDER = "dev.tr7zw.notenoughanimations.util.RenderStateHolder";

    /** {@code ExtendedLivingRenderState.setEntity(LivingEntity)}, or null when the bridge is off. */
    private static MethodHandle setEntity;
    /** {@code PlayerData.getData(DataHolder, Supplier)} — the shadow's per-player scratch space. */
    private static MethodHandle getData;
    /** {@code new RenderStateHolder.RenderStateData()}, for the supplier {@link #getData} wants. */
    private static MethodHandle newStateData;
    /** Setter for {@code RenderStateData.renderState}. */
    private static MethodHandle setRenderState;
    /** The {@code PlayerData} interface, to check the shadow really carries one. */
    private static Class<?> playerDataType;
    /** {@code RenderStateHolder.INSTANCE} — the key the render state is filed under. */
    private static Object stateHolder;
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
            // Filing first is the safety ordering: setEntity is what makes NEA animate this
            // shadow, and three of its animations dereference the filed render state without a
            // null check. If filing is impossible the bridge switches itself off and a Person
            // animates the vanilla way.
            if (!fileRenderState(renderState, shadow)) {
                disable("the shadow carries no NEA PlayerData to file a render state under");
                return;
            }
            setEntity.invoke(renderState, (LivingEntity) shadow);
        } catch (Throwable t) {
            disable("retarget threw", t);
        }
    }

    /**
     * Files this render state under the shadow, the way NEA files a player's under the player.
     *
     * <p>Three of NEA's animations (crawl, ladder, elytra) read the render state back with no
     * null check, because for a player {@code PlayerRendererMixin} cannot leave it null. A Person
     * never goes through that method: extraction dispatches by ENTITY TYPE into
     * {@code PersonRenderer}, and only SUBMISSION reaches {@code AvatarRenderer}. So NEA's hook
     * never fires, the shadow's slot stays null, and the first animation to read it dereferences
     * null on the render thread — which the swimming pose did, crashing the client the instant a
     * settler got wet.
     */
    private static boolean fileRenderState(Object renderState, AbstractClientPlayer shadow)
            throws Throwable {
        if (!playerDataType.isInstance(shadow)) {
            return false; // NEA mixes PlayerData onto Player; if that ever moves, back out entirely
        }
        Supplier<Object> fresh = () -> {
            try {
                return newStateData.invoke();
            } catch (Throwable t) {
                throw new IllegalStateException("NEA RenderStateData constructor threw", t);
            }
        };
        setRenderState.invoke(getData.invoke(shadow, stateHolder, fresh), renderState);
        return true;
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
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            Class<?> stateInterface = Class.forName(STATE_INTERFACE);
            setEntity = lookup.unreflect(stateInterface.getMethod("setEntity", LivingEntity.class));

            playerDataType = Class.forName(PLAYER_DATA);
            Class<?> dataType = Class.forName(STATE_HOLDER + "$RenderStateData");
            stateHolder = Class.forName(STATE_HOLDER).getField("INSTANCE").get(null);
            getData = lookup.unreflect(playerDataType.getMethod(
                    "getData", Class.forName(DATA_HOLDER), Supplier.class));
            newStateData = lookup.unreflectConstructor(dataType.getConstructor());
            setRenderState = lookup.unreflectSetter(dataType.getField("renderState"));

            AutarkiaMod.LOGGER.info(
                    "NotEnoughAnimations detected — Persons will animate through it.");
        } catch (Throwable t) {
            disable("could not bind NotEnoughAnimations", t);
        }
    }

    /** As {@link #disable(String, Throwable)}, for a refusal that is a decision rather than a throw. */
    private static void disable(String why) {
        disable(why, new IllegalStateException(why));
    }

    /** Off for the rest of the session — one warning, then silence, then vanilla poses. */
    private static void disable(String why, Throwable t) {
        disabled = true;
        setEntity = null;
        getData = null;
        newStateData = null;
        setRenderState = null;
        stateHolder = null;
        AutarkiaMod.LOGGER.warn(
                "NotEnoughAnimations bridge disabled ({}); Persons fall back to vanilla arm poses.",
                why, t);
    }
}
