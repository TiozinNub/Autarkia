package dev.luizloyola.autarkia.core.brain.sense;

import org.jspecify.annotations.Nullable;

/**
 * One change in who-she-perceives — the peer sensor's output beside the list itself, so
 * downstream behavior can REACT instead of polling and diffing. Journaled as SENSE lines, same
 * as the POI sensor's noticed/forgot.
 */
public record PeerEvent(Type type, Peer peer, Peer.@Nullable Activity was) {

    public enum Type {
        /** A new someone entered perception (any channel). {@code was} is null. */
        SPOTTED,
        /** The linger window expired — gone from perception. {@code peer} is the final reading. */
        LOST,
        /** A live peer's activity flipped; {@code was} is the previous one. */
        ACTIVITY_CHANGED
    }

    public static PeerEvent spotted(Peer peer) {
        return new PeerEvent(Type.SPOTTED, peer, null);
    }

    public static PeerEvent lost(Peer peer) {
        return new PeerEvent(Type.LOST, peer, null);
    }

    public static PeerEvent activityChanged(Peer peer, Peer.Activity was) {
        return new PeerEvent(Type.ACTIVITY_CHANGED, peer, was);
    }
}
