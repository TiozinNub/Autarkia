package dev.luizloyola.autarkia.mod.net;

import dev.luizloyola.anima.core.agent.AgentId;
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
 * S2C debug payload: which {@link AgentId} a client's player has pinned (their {@code
 * PersonSelection} slot), or none — an empty {@link #selected} means "no selection". The pin lives
 * only on the server; this is the read-only shadow behind the debug selection glow ({@code
 * mod.client.DebugGlow}, {@code mod.client.DebugGlowClient}).
 */
public record DebugGlowPayload(Optional<UUID> selected) implements CustomPacketPayload {
    public static final Type<DebugGlowPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("autarkia", "debug_glow"));

    public static final StreamCodec<RegistryFriendlyByteBuf, DebugGlowPayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.optional(UUIDUtil.STREAM_CODEC), DebugGlowPayload::selected,
                    DebugGlowPayload::new);

    public static DebugGlowPayload of(@Nullable AgentId id) {
        return new DebugGlowPayload(Optional.ofNullable(id).map(AgentId::value));
    }

    /** The pinned id as a {@link AgentId}, or {@code null} when nothing is selected. */
    public @Nullable AgentId personId() {
        return selected.map(AgentId::of).orElse(null);
    }

    @Override
    public Type<DebugGlowPayload> type() {
        return TYPE;
    }
}
