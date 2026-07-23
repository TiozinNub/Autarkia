package dev.luizloyola.autarkia.mod.net;

import dev.luizloyola.autarkia.core.person.PersonId;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * S2C debug payload: which {@link PersonId} the player has pinned in {@code PersonSelection},
 * empty for none. The pin lives only on the server; this is the read-only shadow the client keeps
 * for the debug selection glow ({@code mod.client.DebugGlow}, {@code mod.client.DebugGlowClient}).
 */
public record DebugGlowPayload(Optional<UUID> selected) implements CustomPacketPayload {
    public static final Type<DebugGlowPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("autarkia", "debug_glow"));

    public static final StreamCodec<RegistryFriendlyByteBuf, DebugGlowPayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.optional(UUIDUtil.STREAM_CODEC), DebugGlowPayload::selected,
                    DebugGlowPayload::new);

    public static DebugGlowPayload of(@Nullable PersonId id) {
        return new DebugGlowPayload(Optional.ofNullable(id).map(PersonId::value));
    }

    public @Nullable PersonId personId() {
        return selected.map(PersonId::of).orElse(null);
    }

    @Override
    public Type<DebugGlowPayload> type() {
        return TYPE;
    }
}
