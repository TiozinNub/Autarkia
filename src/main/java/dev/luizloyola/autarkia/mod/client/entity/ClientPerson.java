package dev.luizloyola.autarkia.mod.client.entity;

import dev.luizloyola.autarkia.mod.entity.Person;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.entity.ClientAvatarEntity;
import net.minecraft.client.entity.ClientAvatarState;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.ClientAsset;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.parrot.Parrot;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.level.Level;

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

    public ClientPerson(EntityType<? extends Person> type, Level level) {
        super(type, level);
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

    /** Build a player skin pointing at this Person's chosen texture file, with the arm model
     *  (wide/slim) their appearance calls for. The renderer reads this model type back to pick the
     *  matching baked model. Also serves the {@link ClientAvatarEntity} contract. */
    @Override
    public PlayerSkin getSkin() {
        ClientAsset.Texture body = new ClientAsset.ResourceTexture(getSkinTexture());
        PlayerModelType model = isSlim() ? PlayerModelType.SLIM : PlayerModelType.WIDE;
        return PlayerSkin.insecure(body, null, null, model);
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
}
