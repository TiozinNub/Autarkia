package dev.luizloyola.autarkia.mod.client.entity;

import dev.luizloyola.autarkia.mod.entity.Person;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.ClientAsset;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.level.Level;

/**
 * Client-side twin of {@link Person}. Exists so the (client-only) {@link PlayerSkin} can be
 * resolved on the client, mirroring vanilla's {@code ClientMannequin}. Installed via
 * {@link #install()} so client worlds spawn this variant instead of the plain entity.
 */
@Environment(EnvType.CLIENT)
public class ClientPerson extends Person {
    public ClientPerson(EntityType<? extends Person> type, Level level) {
        super(type, level);
    }

    /** Point the shared factory at the client twin for client-side levels. */
    public static void install() {
        Person.factory = (type, level) -> level instanceof ClientLevel
                ? new ClientPerson(type, level)
                : new Person(type, level);
    }

    /** Build a player skin pointing at this Person's chosen texture file. */
    public PlayerSkin getSkin() {
        ClientAsset.Texture body = new ClientAsset.ResourceTexture(getSkinTexture());
        return PlayerSkin.insecure(body, null, null, PlayerModelType.WIDE);
    }
}
