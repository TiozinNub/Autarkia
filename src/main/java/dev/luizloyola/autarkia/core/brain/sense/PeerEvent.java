package dev.luizloyola.autarkia.core.brain.sense;

import org.jspecify.annotations.Nullable;

/**
 * One change in who-is-perceived — the peer sensor's output beside the list itself, so downstream
 * behavior can REACT instead of polling and diffing. Journaled as SENSE lines, like the POI
 * sensor's noticed/forgot.
 *
 * <p>{@link Type#READING_CHANGED} fires whenever ANY rendered axis of a tracked peer flips —
 * occupation, legs, posture, gaze, or the awareness tag; eventing only occupation lost most changes
 * (caught live). {@link #was} carries the full previous reading.
 */
public record PeerEvent(Type type, Peer peer, @Nullable Peer was) {

    public enum Type {
        /** A new someone entered perception (any channel). {@code was} is null. */
        SPOTTED,
        /** The linger window expired — gone from perception. {@code peer} is the final reading. */
        LOST,
        /** Some axis of a tracked peer's reading flipped; {@code was} is the previous reading. */
        READING_CHANGED,
        /** A heard-only "someone" finally got SEEN — now they know who it was all along. */
        RECOGNIZED
    }

    public static PeerEvent spotted(Peer peer) {
        return new PeerEvent(Type.SPOTTED, peer, null);
    }

    public static PeerEvent lost(Peer peer) {
        return new PeerEvent(Type.LOST, peer, null);
    }

    public static PeerEvent readingChanged(Peer peer, Peer was) {
        return new PeerEvent(Type.READING_CHANGED, peer, was);
    }

    public static PeerEvent recognized(Peer peer) {
        return new PeerEvent(Type.RECOGNIZED, peer, null);
    }
}
