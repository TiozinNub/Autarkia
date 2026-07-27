package dev.luizloyola.autarkia.mod.net;

import dev.luizloyola.autarkia.core.person.PersonId;
import dev.luizloyola.autarkia.mod.person.PersonDirectory;
import dev.luizloyola.autarkia.mod.social.ContactData;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Server-side wiring for the contact book. The whole book goes out on login and after a
 * respawn (a new {@code ServerPlayer}, so client state is fresh); every later learn pushes one
 * entry, so a nameplate appears at the introduction, not at the next reconnect. Only names the
 * {@link PersonDirectory} can resolve are sent — vanilla already shows player names.
 *
 * <p>Call {@link #install()} from common mod init: the payload type must be registered on both
 * sides.
 */
public final class ContactsSync {
    private ContactsSync() {}

    public static void install() {
        PayloadTypeRegistry.clientboundPlay().register(ContactsPayload.TYPE, ContactsPayload.CODEC);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> resync(handler.player));
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> resync(newPlayer));
    }

    /** Pushes this player's whole book, replacing whatever their client held. */
    public static void resync(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel level)
                || !ServerPlayNetworking.canSend(player, ContactsPayload.TYPE)) {
            return;
        }
        MinecraftServer server = level.getServer();
        PersonDirectory directory = PersonDirectory.get(server);
        List<ContactsPayload.Known> known = new ArrayList<>();
        for (PersonId contact : ContactData.get(server).contactsOf(idOf(player))) {
            directory.nameOf(contact)
                    .ifPresent(name -> known.add(ContactsPayload.Known.of(contact, name)));
        }
        ServerPlayNetworking.send(player, ContactsPayload.whole(known));
    }

    /**
     * Pushes one just-learned name, if the knower is an online player and the id names a Person.
     * Safe to call for any knower — a Person learning a name has no client to tell.
     */
    public static void learned(MinecraftServer server, PersonId knower, PersonId whom) {
        ServerPlayer player = server.getPlayerList().getPlayer(knower.value());
        if (player == null || !ServerPlayNetworking.canSend(player, ContactsPayload.TYPE)) {
            return;
        }
        PersonDirectory.get(server).nameOf(whom).ifPresent(name ->
                ServerPlayNetworking.send(player, ContactsPayload.learned(whom, name)));
    }

    /** A player's identity handle: their account UUID, exactly as the sense mints it. */
    public static PersonId idOf(ServerPlayer player) {
        return PersonId.of(player.getUUID());
    }
}
