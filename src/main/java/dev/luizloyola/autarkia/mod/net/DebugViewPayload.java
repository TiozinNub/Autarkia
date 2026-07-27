package dev.luizloyola.autarkia.mod.net;

import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * S2C debug snapshot: one frame of everything the in-world debug view draws over the watching
 * player's selected Person. Sent on a slow cadence by {@code mod.debug.DebugView}, drawn every
 * frame by {@code mod.client.DebugViewRenderer}.
 *
 * <p>A snapshot rather than a query because every fact here lives on the server with no
 * client-side counterpart. What the client can already see is absent: position,
 * facing and eye height come off the local entity ({@link #entityId}), so the lines track it
 * smoothly instead of stuttering at the send cadence.
 *
 * <p>{@link #layers} is the authoritative {@code DebugLayer} bit mask; an off layer's collections
 * arrive empty, so the renderer checks the mask rather than guessing what empty means. A payload
 * with no layers is the explicit clear — without it the client would redraw the final frame
 * forever.
 *
 * <p>Debug traffic: never batched, never persisted, only sent to a player who asked by name.
 */
public record DebugViewPayload(
        int entityId,
        int layers,
        List<Step> path,
        int pathIndex,
        Optional<BlockPos> goal,
        String nav,
        List<String> brain,
        List<Belief> beliefs,
        List<PeerMark> peers,
        int coneDegrees,
        int senseRadius) implements CustomPacketPayload {

    public static final Type<DebugViewPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("autarkia", "debug_view"));

    /**
     * One leg of the walked path: the cell, and how they mean to get into it. The renderer colours
     * by move type.
     */
    public record Step(BlockPos pos, int move) {
        public static final StreamCodec<RegistryFriendlyByteBuf, Step> CODEC =
                StreamCodec.composite(
                        BlockPos.STREAM_CODEC, Step::pos,
                        ByteBufCodecs.VAR_INT, Step::move,
                        Step::new);
    }

    /**
     * One remembered point of interest: what they think it is, where they'd walk to, the box
     * they believe it fills, and whether the belief has gone stale. Stale is computed server-side
     * against the last sighting, because the client has no game time it can trust.
     */
    public record Belief(int kind, BlockPos anchor, BlockPos min, BlockPos max, boolean stale) {
        public static final StreamCodec<RegistryFriendlyByteBuf, Belief> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, Belief::kind,
                        BlockPos.STREAM_CODEC, Belief::anchor,
                        BlockPos.STREAM_CODEC, Belief::min,
                        BlockPos.STREAM_CODEC, Belief::max,
                        ByteBufCodecs.BOOL, Belief::stale,
                        Belief::new);
    }

    /**
     * One perceived someone, as THEY have them: the name they would use, the reading in words, the
     * believed cell, and (only when that belief is live) the body to interpolate it from.
     *
     * <p>{@link #name} is {@code Peer.knownAs()}, not the account name: sound does not identify,
     * so an unseen someone reads as "someone". {@link #tell} is {@code Peer.tell()} composed
     * server-side — every observable axis (arm, legs, sneak, gaze) in one phrase, so there is one
     * description of a peer in the codebase and no copy here to drift when the sense grows an
     * axis.
     *
     * <p>{@link #pos} is always what they BELIEVE: a REMEMBERED peer's position is frozen at the
     * last live reading and a HEARD one is where the noise came from, so drawing the live entity
     * would erase the discrepancy this layer exists to show. {@link #entityId} is therefore
     * {@link #NO_BODY} for every awareness but SEEN; the gate is server-side so a client cannot
     * follow a ghost by mistake.
     */
    public record PeerMark(String name, String tell, BlockPos pos, int entityId,
                           int awareness, float distance) {
        /** No body to interpolate from — draw the believed cell as sent. */
        public static final int NO_BODY = -1;

        public static final StreamCodec<RegistryFriendlyByteBuf, PeerMark> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.STRING_UTF8, PeerMark::name,
                        ByteBufCodecs.STRING_UTF8, PeerMark::tell,
                        BlockPos.STREAM_CODEC, PeerMark::pos,
                        ByteBufCodecs.VAR_INT, PeerMark::entityId,
                        ByteBufCodecs.VAR_INT, PeerMark::awareness,
                        ByteBufCodecs.FLOAT, PeerMark::distance,
                        PeerMark::new);
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, DebugViewPayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, DebugViewPayload::entityId,
                    ByteBufCodecs.VAR_INT, DebugViewPayload::layers,
                    Step.CODEC.apply(ByteBufCodecs.list()), DebugViewPayload::path,
                    ByteBufCodecs.VAR_INT, DebugViewPayload::pathIndex,
                    ByteBufCodecs.optional(BlockPos.STREAM_CODEC), DebugViewPayload::goal,
                    ByteBufCodecs.STRING_UTF8, DebugViewPayload::nav,
                    ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), DebugViewPayload::brain,
                    Belief.CODEC.apply(ByteBufCodecs.list()), DebugViewPayload::beliefs,
                    PeerMark.CODEC.apply(ByteBufCodecs.list()), DebugViewPayload::peers,
                    ByteBufCodecs.VAR_INT, DebugViewPayload::coneDegrees,
                    ByteBufCodecs.VAR_INT, DebugViewPayload::senseRadius,
                    DebugViewPayload::new);

    /** The "draw nothing" snapshot — every layer off, no entity to anchor to. */
    public static DebugViewPayload clear() {
        return new DebugViewPayload(-1, 0, List.of(), 0, Optional.empty(), "", List.of(),
                List.of(), List.of(), 0, 0);
    }

    /** Whether this snapshot carries anything to draw at all. */
    public boolean isEmpty() {
        return layers == 0 || entityId < 0;
    }

    @Override
    public Type<DebugViewPayload> type() {
        return TYPE;
    }
}
