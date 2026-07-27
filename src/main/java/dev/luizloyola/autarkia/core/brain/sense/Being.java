package dev.luizloyola.autarkia.core.brain.sense;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * A nearby living body, as one perception (spec: {@code 2026-07-27-being-sense-design.md}). One
 * pipeline tracks every living entity, with typed enrichment. PERSON is another Person or a live
 * PLAYER, indistinguishable (species normalized to {@code person}, id minted from the
 * person identity), and the only kind with the full classifier reading; a creature's is thin —
 * presence, place, distance plus its kind's reader.
 *
 * <p><b>Identification is a LADDER, universal across kinds</b>: a step or generic sound gives
 * position only; a VOICE names the SPECIES; sight tells everything knowable by seeing.
 * {@link #identified} is the achieved tier and the sensor MASKS the reading down to it — at
 * {@link Identified#NONE} even {@link #kind} reads {@link Kind#UNKNOWN}. The id stays stable
 * underneath for track continuity, so identity-dependent behavior must gate on the tier, never on
 * the id existing. {@link BeingEvent.Type#RECOGNIZED} fires on every tier upgrade, by eye or ear.
 *
 * <p><b>The reading decomposes along independent axes</b>, all OBSERVABLE body signals rather than
 * a peek into another brain: {@link #activity} (ARMS/ATTENTION, persons only), {@link #locomotion}
 * (LEGS), {@link #sneaking} (posture), {@link #watching} (gaze). Hence {@link #aggressive} reads
 * the game's SYNCED anger on a neutral mob, and {@link #approaching} is a measured distance trend
 * rather than a {@code getTarget()} peek.
 *
 * <p><b>Herds are one perception</b>: 3+ same-species herd animals collapse into a single being
 * with {@link #count} > 1; 1–2 stay individuals. {@link #awareness} carries the best member
 * channel, {@link #pos} the centroid.
 */
public record Being(BeingId id, Kind kind, String species, String name,
                    @Nullable String profession, Pos pos, double distance, int count,
                    int spread, boolean herdAnimal, java.util.List<BeingId> members,
                    Activity activity, Locomotion locomotion,
                    boolean sneaking, boolean watching, boolean aimedAt, boolean approaching,
                    boolean aggressive, Gear gear, Identified identified, Awareness awareness) {

    public Being {
        members = java.util.List.copyOf(members);
    }

    /** How far up the ladder the observer has made this one out. */
    public enum Identified {
        /** Position only — a step behind the wall. Everything else reads masked. */
        NONE,
        /** A voice named the species — "a zombie", never the individual, never gear. */
        SPECIES,
        /** Seen: everything knowable by seeing. Tiers never go back down. */
        INDIVIDUAL
    }

    public enum Kind {
        /** Identification below {@link Identified#SPECIES} — the observer can't say yet. */
        UNKNOWN,
        /** A Person or a live player — seamlessly, and the only kind fully classified. */
        PERSON,
        /** The game's {@code Enemy} — always {@link #aggressive}. */
        MONSTER,
        /** The game's {@code NeutralMob} — aggressive only while visibly angry. */
        NEUTRAL,
        /** Everything else living — herd animals among them collapse into herds. */
        PASSIVE,
        /** An {@code AbstractVillager} — villager or wandering trader, species tells. */
        VILLAGER
    }

    /** The sight-tier equipment reads — the danger modifiers (decision: Luiz). */
    public record Gear(boolean melee, boolean ranged, boolean armored, boolean mounted,
                       boolean baby) {
        public static final Gear NONE = new Gear(false, false, false, false, false);
    }

    /** Whether this is a herd aggregate rather than one body. {@link #spread} is then the
     *  members' max Chebyshev distance from the centroid, {@link #members} the ids it speaks for
     *  — a SEEN herd is seen cow by cow, which lets knowledge retire exactly its members' loner
     *  memories, never an unrelated stray. */
    public boolean herd() {
        return count > 1;
    }

    /**
     * The name they'd use, by tier: below SPECIES, {@code "someone"}; at SPECIES a creature is
     * {@code "a zombie"} but a person is still {@code "someone"}, a person's species naming
     * nobody; at INDIVIDUAL, a person's name and a creature's custom name if it wears one.
     */
    public String knownAs() {
        if (identified == Identified.NONE) {
            return "someone";
        }
        if (kind == Kind.PERSON) {
            return identified == Identified.INDIVIDUAL ? name : "someone";
        }
        String species = this.species.replace('_', ' ');
        if (herd()) {
            return "a herd of " + species + (species.endsWith("s") ? "" : "s");
        }
        if (identified == Identified.INDIVIDUAL && !name.isEmpty()) {
            return name;
        }
        if (identified == Identified.INDIVIDUAL && profession != null) {
            // Sight tells the profession, so sight SAYS it (decision: Luiz — "see: profession"):
            // a villager you have actually looked at is "a farmer", not "a villager".
            String job = profession.replace('_', ' ');
            return ("aeiou".indexOf(job.charAt(0)) >= 0 ? "an " : "a ") + job;
        }
        return ("aeiou".indexOf(species.charAt(0)) >= 0 ? "an " : "a ") + species;
    }

    /**
     * The one human-readable reading: a person composes their axes ({@code "eating, walking,
     * sneaking"}, {@code "watching him/her"} in the OBSERVER's pronoun — callers pass
     * {@code gender.objectPronoun()}); a creature renders {@code "nearby"} or
     * {@code "closing in on him/her"}; a herd tells its head count. Renderers append their own
     * awareness tag.
     */
    public String tell(String observerPronoun) {
        if (kind != Kind.PERSON) {
            if (herd()) {
                return count + " head, centered there";
            }
            if (approaching && aggressive) {
                return "closing in on " + observerPronoun;
            }
            return aggressive ? "aggressive, nearby" : "nearby";
        }
        StringBuilder tell = new StringBuilder();
        if (activity == Activity.IDLE) {
            tell.append(locomotion == Locomotion.STILL
                    ? "idle" : locomotion.name().toLowerCase(Locale.ROOT));
        } else {
            tell.append(activity == Activity.AIMING && aimedAt
                    ? "aiming at " + observerPronoun
                    : activity.name().toLowerCase(Locale.ROOT));
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

    /** The ARMS/ATTENTION occupation — persons only; every other kind reads {@link #IDLE}. */
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
        /** Bow or crossbow drawn — {@link #aimedAt} says whether it points at the OBSERVER
         *  (a tight cone off the draw, instant: a bow crossing you alarms immediately). */
        AIMING,
        /** Placing blocks — the same swing as mining, told apart by fresh place-marks (seen)
         *  or the block-place sound (heard). */
        BUILDING,
        /** At a visibly open chest — seen near it, CONFIRMED by the container (the lid tells). */
        AT_CHEST,
        /** Facing a crafting table within reach — assumed, and fallible. */
        AT_CRAFTING,
        /** In a bed. */
        SLEEPING
    }
}
