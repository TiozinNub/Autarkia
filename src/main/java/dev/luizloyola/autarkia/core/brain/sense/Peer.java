package dev.luizloyola.autarkia.core.brain.sense;

import dev.luizloyola.autarkia.core.person.PersonId;
import java.util.Locale;

/**
 * A nearby person-shaped someone, as one perception — another Person or a live PLAYER,
 * indistinguishable; a player's {@link #id} is minted from the account UUID, so it
 * is as stable across sessions as a Person's own.
 *
 * <p>{@link #awareness} says which channel produced it: SEEN through the vision cone with line
 * of sight, HEARD making noise inside the hearing bubble, REMEMBERED for the linger window after
 * every channel went dark. A REMEMBERED reading is frozen at its last live values; consumers
 * wanting only live truth filter on awareness.
 *
 * <p><b>The reading decomposes along the body's independent axes</b>: {@link #activity} the
 * ARMS/ATTENTION occupation, one value off the classifier's ladder; {@link #locomotion} the
 * LEGS, from measured speed; {@link #sneaking} posture; {@link #watching} gaze dwelling on the
 * observer, never a passing glance. All are OBSERVABLE body signals, never a peek into another
 * brain, and sound carries fewer of them: place, doing and moving feet, but not gaze or posture.
 *
 * <p>{@link #identified} carries the ear's limit: SOUND DOESN'T SAY WHO. A heard-never-seen
 * peer is "someone" ({@link #knownAs}); the first clear look flips it, marked by
 * {@link PeerEvent.Type#RECOGNIZED}. The {@link #id} stays stable either way, but
 * identity-dependent behavior must gate on {@code identified}, never on the id existing.
 */
public record Peer(PersonId id, String name, Pos pos, double distance, Activity activity,
                   Locomotion locomotion, boolean sneaking, boolean watching,
                   boolean identified, Awareness awareness) {

    /** The name she'd use — sound doesn't identify: unseen means "someone". */
    public String knownAs() {
        return identified ? name : "someone";
    }

    /**
     * The one human-readable reading, all axes composed — {@code "eating, walking, sneaking"}, with
     * {@code "watching him/her"} last in the OBSERVER's pronoun (callers pass
     * {@code gender.objectPronoun()}). An idle-armed walker is {@code "walking"}, an idle-armed
     * stander {@code "idle"}. Renderers append their own awareness tag.
     */
    public String tell(String observerPronoun) {
        StringBuilder tell = new StringBuilder();
        if (activity == Activity.IDLE) {
            tell.append(locomotion == Locomotion.STILL
                    ? "idle" : locomotion.name().toLowerCase(Locale.ROOT));
        } else {
            tell.append(activity.name().toLowerCase(Locale.ROOT));
            if (locomotion != Locomotion.STILL) {
                tell.append(", ").append(locomotion.name().toLowerCase(Locale.ROOT));
            }
        }
        if (sneaking) {
            tell.append(", sneaking");
        }
        if (watching) {
            tell.append(", watching ").append(observerPronoun);
        }
        return tell.toString();
    }

    /** Which channel produced this perception — the freshness story, live-first. */
    public enum Awareness {
        /** In the vision cone with a clear ray to some body part — the full live reading. */
        SEEN,
        /** Made noise inside the hearing bubble — position live (sound places its source), sight unconfirmed. */
        HEARD,
        /** Every channel dark, linger window still open — the last live reading, frozen. */
        REMEMBERED
    }

    /** The LEGS axis — from measured speed over a real window, sound's steps included. */
    public enum Locomotion {
        STILL,
        WALKING,
        SPRINTING
    }

    /** The ARMS/ATTENTION occupation — one primary, classified coarsest-first. */
    public enum Activity {
        /** Unoccupied arms — the approachable state the social layer looks for. */
        IDLE,
        /** Swinging at the world — breaking blocks, or any sustained unclassified arm work. */
        MINING,
        /** Swinging and recently dealt damage — the same arm, a different story. */
        FIGHTING,
        /** The eat use-animation. */
        EATING,
        /** The drink use-animation. */
        DRINKING,
        /** Shield raised. */
        BLOCKING,
        /** Bow or crossbow drawn. */
        AIMING,
        /** At a visibly open chest — seen near it, CONFIRMED by the container (the lid tells). */
        AT_CHEST,
        /** Facing a crafting table within reach — assumed, and fallible. */
        AT_CRAFTING,
        /** In a bed. */
        SLEEPING
    }
}
