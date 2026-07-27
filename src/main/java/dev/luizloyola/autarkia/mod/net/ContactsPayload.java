package dev.luizloyola.autarkia.mod.net;

import dev.luizloyola.autarkia.core.person.PersonId;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * S2C: the names this player has earned. A Person's custom name would be broadcast to everyone
 * tracking them (the leak the contact book closes), so it travels here instead, only to the client
 * that has met them.
 *
 * <p>{@link #replace} true is the whole book (login, respawn, {@code /autarkia contacts clear}),
 * false a single entry pushed the moment a name is learned, so a nameplate appears at the
 * introduction rather than at the next reconnect.
 */
public record ContactsPayload(boolean replace, List<Known> contacts) implements CustomPacketPayload {
    public static final Type<ContactsPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("autarkia", "contacts"));

    /** One earned name: the Person's id as the client already knows it (synced), and what to call them. */
    public record Known(UUID id, String name) {
        public static final StreamCodec<RegistryFriendlyByteBuf, Known> CODEC =
                StreamCodec.composite(
                        UUIDUtil.STREAM_CODEC, Known::id,
                        ByteBufCodecs.STRING_UTF8, Known::name,
                        Known::new);

        public static Known of(PersonId id, String name) {
            return new Known(id.value(), name);
        }
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, ContactsPayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.BOOL, ContactsPayload::replace,
                    Known.CODEC.apply(ByteBufCodecs.list()), ContactsPayload::contacts,
                    ContactsPayload::new);

    /** The whole book, replacing whatever the client held. */
    public static ContactsPayload whole(List<Known> contacts) {
        return new ContactsPayload(true, contacts);
    }

    public static ContactsPayload learned(PersonId id, String name) {
        return new ContactsPayload(false, List.of(Known.of(id, name)));
    }

    @Override
    public Type<ContactsPayload> type() {
        return TYPE;
    }
}
