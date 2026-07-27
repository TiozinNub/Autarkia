package dev.luizloyola.autarkia.core.brain.sense;

import org.jspecify.annotations.Nullable;

/**
 * One change in what-is-perceived — the being sensor's output beside the list itself, so
 * downstream behavior can REACT instead of polling and diffing. Journaled as SENSE lines, but
 * narration is kind-gated: persons narrate every axis flip, creatures only spotted / recognized /
 * lost, or a chase's approaching-flip-storm would drown the journal.
 *
 * <p>{@link Type#READING_CHANGED} fires when ANY rendered axis flips — occupation, legs, posture,
 * gaze, approach, or the awareness tag; {@link #was} carries the full previous reading.
 */
public record BeingEvent(Type type, Being being, @Nullable Being was) {

    public enum Type {
        /** A new someone/something entered perception (any channel). {@code was} is null. */
        SPOTTED,
        /** The linger expired — gone from perception. {@code being} is the final reading. */
        LOST,
        /** Some axis of a tracked being's reading flipped; {@code was} is the previous one. */
        READING_CHANGED,
        /** The identification ladder climbed a tier — a voice named the species, or sight
         *  named the individual. {@code was} is the reading as it stood below the rung. */
        RECOGNIZED
    }

    public static BeingEvent spotted(Being being) {
        return new BeingEvent(Type.SPOTTED, being, null);
    }

    public static BeingEvent lost(Being being) {
        return new BeingEvent(Type.LOST, being, null);
    }

    public static BeingEvent readingChanged(Being being, Being was) {
        return new BeingEvent(Type.READING_CHANGED, being, was);
    }

    public static BeingEvent recognized(Being being, Being was) {
        return new BeingEvent(Type.RECOGNIZED, being, was);
    }
}
