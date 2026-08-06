package dev.luizloyola.autarkia.mod.client.entity;

import dev.luizloyola.anima.mod.client.appearance.BakedTextures;
import dev.luizloyola.anima.core.appearance.Blink;
import dev.luizloyola.anima.core.appearance.Recipe;
import dev.luizloyola.autarkia.core.person.AppearanceComposer;
import dev.luizloyola.autarkia.mod.person.PersonAppearance;
import dev.luizloyola.autarkia.mod.client.anim.ShadowPlayer;
import dev.luizloyola.autarkia.mod.entity.Person;
import java.util.Map;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.entity.ClientAvatarEntity;
import net.minecraft.client.entity.ClientAvatarState;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.ClientAsset;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.parrot.Parrot;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

/**
 * Client-side twin of {@link Person}, so the client-only {@link PlayerSkin} resolves here, mirroring
 * vanilla's {@code ClientMannequin}; {@link #install()} points the factory at it for client worlds.
 *
 * <p>Implements {@link ClientAvatarEntity} because vanilla's contract is that every client-side
 * {@link Avatar} carries it: skin/parrot/ear render paths and player-model mods such as 3D Skin
 * Layers cast an on-screen Avatar straight to it, and those casts would crash on a Person.
 */
@Environment(EnvType.CLIENT)
public class ClientPerson extends Person implements ClientAvatarEntity {
    /** Per-entity cloak/bob/walk interpolation state for the render layer. Unread here
     *  ({@code PersonRenderer} animates off the entity's own walk state), but ticked like
     *  {@code ClientMannequin} so a consumer of {@link #avatarState()} sees sane values. */
    private final ClientAvatarState avatarState = new ClientAvatarState();

    /** This Person's stand-in player for NotEnoughAnimations, built on first render and only when
     *  that mod is present — see {@link ShadowPlayer}. Lives here, not on the renderer, because NEA
     *  keeps per-entity animation state on it and one model instance serves every Person on screen. */
    private @Nullable ShadowPlayer shadow;

    /** This Person's live baked texture — one reference into Anima's shared cache, given back in
     *  {@link #onClientRemoval()}. Two Persons who genuinely look the same hold two handles on one
     *  texture, so the handle counts rather than owns. */
    private final BakedTextures.Handle bakedSkin = BakedTextures.handle();

    /** The recipe this body was last composed into, and the two things that invalidate it: the
     *  appearance it came from, and whether the eyes were shut. Composing per frame would allocate a
     *  recipe and hash a canonical string for every Person on screen, sixty times a second, to answer
     *  a question whose answer changes about once every four seconds. */
    private @Nullable Recipe composed;
    private @Nullable Recipe composedFrom;
    private boolean eyesShut;

    public ClientPerson(EntityType<? extends Person> type, Level level) {
        super(type, level);
    }

    /** The lazily-built shadow; never null, but its {@code synced()} may be. */
    public ShadowPlayer shadow() {
        if (this.shadow == null) {
            this.shadow = new ShadowPlayer(this);
        }
        return this.shadow;
    }

    /** Point the shared factory at the client twin for client-side levels. */
    public static void install() {
        Person.factory = (type, level) -> level instanceof ClientLevel
                ? new ClientPerson(type, level)
                : new Person(type, level);
    }

    /** Advance the avatar interpolation state each client tick, mirroring {@code ClientMannequin}.
     *  {@link Person#tick()}'s server-side identity work is gated on {@code ServerLevel}, so it stays
     *  dormant here — a ClientPerson only ever lives in a {@link ClientLevel}. */
    @Override
    public void tick() {
        super.tick();
        this.avatarState.tick(position(), getDeltaMovement());
    }

    /**
     * Build a player skin pointing at this Person's <b>baked</b> texture, with the arm model
     * (wide/slim) their appearance calls for; the renderer reads this model type back to pick the
     * matching baked model. Also serves the {@link ClientAvatarEntity} contract.
     *
     * <p>Called once per frame per visible Person, so the handle is a field.
     *
     * <p>⚠️ The <b>two-argument</b> {@code ResourceTexture} constructor, deliberately: the
     * one-argument form derives {@code textures/<id>.png} from the id, right for art in a pack and
     * wrong for a texture registered under its name — a double-wrap this project has paid for once
     * already.
     */
    @Override
    public PlayerSkin getSkin() {
        Identifier baked = this.bakedSkin.textureFor(liveRecipe());
        ClientAsset.Texture body = new ClientAsset.ResourceTexture(baked, baked);
        PlayerModelType model = isSlim() ? PlayerModelType.SLIM : PlayerModelType.WIDE;
        return PlayerSkin.insecure(body, null, null, model);
    }

    /**
     * This body as it is <em>right now</em> — their appearance, plus what their face is doing.
     *
     * <p>Blinking is <b>client-side and unsynced</b>: syncing a cosmetic that changes several
     * times a minute per body would cost a packet per blink to buy an agreement nobody can
     * perceive. Each client runs the same stateless schedule against its own clock, seeded per
     * body so a crowd never blinks in unison.
     *
     * <p>Recomposed only when the appearance (compared by identity) or the eyes move.
     */
    private Recipe liveRecipe() {
        Recipe base = appearanceRecipe();
        boolean shut = Blink.shutAt(getAgentId().value().getLeastSignificantBits(),
                System.currentTimeMillis());
        if (this.composed == null || this.composedFrom != base || this.eyesShut != shut) {
            this.composedFrom = base;
            this.eyesShut = shut;
            Map<String, String> state = new java.util.LinkedHashMap<>(AppearanceComposer.RESTING_STATE);
            state.put("blink", Boolean.toString(shut));
            this.composed = AppearanceComposer.compose(appearance(), PersonAppearance.catalog(),
                    PersonAppearance::has, state);
        }
        return this.composed;
    }

    /**
     * Hand the baked texture back when this body leaves the client.
     *
     * <p>Without it a settlement's worth of composited skins accumulates in native memory, where a
     * leak never shows up in a heap profile. Anima frees a texture only when the last Person
     * wearing that look lets go, so this is a decrement, and safe to reach twice.
     */
    @Override
    public void onClientRemoval() {
        super.onClientRemoval();
        this.bakedSkin.dispose();
    }

    @Override
    public ClientAvatarState avatarState() {
        return this.avatarState;
    }

    /** No parrot rides a Person's shoulder — vanilla's {@code ClientMannequin} returns null too. */
    @Override
    public Parrot.Variant getParrotVariantOnShoulder(boolean leftShoulder) {
        return null;
    }

    /** No deadmau5 ears on a Person — matches {@code ClientMannequin}. */
    @Override
    public boolean showExtraEars() {
        return false;
    }

    /** The below-name line (a scoreboard score) under the nametag; a Person shows none.
     *  {@code ClientAvatarEntity} declares this only on MC ≤ 1.21.x — 26.1 dropped it, so here it is
     *  harmless dead code, hence no {@code @Override}. Vanilla reads {@code null} as "nothing". */
    public Component belowNameDisplay() {
        return null;
    }
}
