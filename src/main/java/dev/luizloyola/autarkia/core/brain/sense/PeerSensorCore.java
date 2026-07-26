package dev.luizloyola.autarkia.core.brain.sense;

import dev.luizloyola.autarkia.core.config.Config;
import dev.luizloyola.autarkia.core.config.Knob;
import dev.luizloyola.autarkia.core.person.PersonId;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The people sense — one per person, pure core; the {@code PoiSensorCore} pattern reapplied to
 * entities (spec: {@code 2026-07-26-peer-sensor-design.md}). EYES (a view cone plus
 * eye-to-hitbox line of sight), EARS (a push-based {@link #heard} channel fed from world sound
 * events), ATTENTION (per-peer cadence scaling with proximity) and OBJECT PERMANENCE (a linger
 * window), in place of a flat omniscient radius dump.
 *
 * <p>The cascade runs cheapest-first, stopping at the first failure: in radius (the query
 * itself, sneak-shrunk per target) → in cone (arithmetic only) → in sight (the rays). Sound
 * short-circuits all of it: a heard body is perceived, cone and walls be damned.
 *
 * <p>A track is LIVE (seen or heard fresh — the reading updates on its cadence, changes emit
 * events) or REMEMBERED (all channels dark — the last live reading FROZEN, still listed, until
 * {@link #lingerTicks()} expires and the peer is LOST). Re-acquiring one emits nothing.
 */
public final class PeerSensorCore {
    /** Ticks between candidate sweeps — discovery cadence; monitored peers have their own. */
    public static final int SWEEP_INTERVAL_TICKS = 10;
    /** How long after a sound its source still counts as a live (heard) channel. */
    public static final int HEARD_FRESH_TICKS = 20;

    /** @see Knob#PEERS_RADIUS */
    public static int radius() {
        return Config.get().i(Knob.PEERS_RADIUS);
    }

    /** @see Knob#PEERS_CONE_DEGREES */
    public static int coneDegrees() {
        return Config.get().i(Knob.PEERS_CONE_DEGREES);
    }

    /** @see Knob#PEERS_LINGER_TICKS */
    public static int lingerTicks() {
        return Config.get().i(Knob.PEERS_LINGER_TICKS);
    }

    /** @see Knob#PEERS_HEARD_DECAY_TICKS */
    public static int heardActivityDecayTicks() {
        return Config.get().i(Knob.PEERS_HEARD_DECAY_TICKS);
    }

    /** @see Knob#PEERS_NEAR_INTERVAL */
    public static int nearIntervalTicks() {
        return Config.get().i(Knob.PEERS_NEAR_INTERVAL);
    }

    /** @see Knob#PEERS_FAR_INTERVAL */
    public static int farIntervalTicks() {
        return Config.get().i(Knob.PEERS_FAR_INTERVAL);
    }

    /** One perceived someone: the latest reading, which channel carries it, and when it's due. */
    private static final class Track {
        PeerReading last;
        Peer.Awareness awareness;
        long nextCheckAt;
        long lastLiveAt;
        // Half-range sentinels: "never" must survive (now - stamp) without overflowing.
        long heardAt = Long.MIN_VALUE / 2;
        /** When {@link #last}'s ACTIVITY was actually witnessed (seen live, or told by a
         *  sound) — sound-told activities decay against this; see {@code decayActivities}. */
        long activityAt = Long.MIN_VALUE / 2;
        /** Whether she has ever SEEN this one — sound doesn't say who. */
        boolean identified;
    }

    private final Map<PersonId, Track> tracks = new LinkedHashMap<>();
    private final List<PeerEvent> pending = new ArrayList<>();
    private long lastSweepAt = Long.MIN_VALUE / 2;

    /**
     * One perception tick: sweep on the discovery cadence, re-check whoever is due, age the dark
     * ones toward forgetting. Returns this tick's events — empty on the common quiet tick.
     */
    public List<PeerEvent> tick(Pos feet, double yawDegrees, double pitchDegrees, long now,
                                PeerWorld world) {
        if (now - lastSweepAt >= SWEEP_INTERVAL_TICKS) {
            lastSweepAt = now;
            for (PeerReading candidate : world.candidates()) {
                if (tracks.containsKey(candidate.id())) {
                    continue; // already perceived — their own cadence owns updates
                }
                if (inCone(feet, yawDegrees, pitchDegrees, candidate.pos())
                        && world.inSight(candidate.id())) {
                    Track track = new Track();
                    track.last = candidate;
                    track.awareness = Peer.Awareness.SEEN;
                    track.lastLiveAt = now;
                    track.activityAt = now;
                    track.identified = true;
                    track.nextCheckAt = now + interval(candidate.distance());
                    tracks.put(candidate.id(), track);
                    pending.add(PeerEvent.spotted(peer(track)));
                }
            }
        }
        for (Iterator<Map.Entry<PersonId, Track>> it = tracks.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<PersonId, Track> entry = it.next();
            Track track = entry.getValue();
            if (track.nextCheckAt > now) {
                continue;
            }
            PeerReading fresh = world.reading(entry.getKey());
            if (fresh == null) {
                goDarkOrForget(track, now, it);
                continue;
            }
            boolean seen = inCone(feet, yawDegrees, pitchDegrees, fresh.pos())
                    && world.inSight(entry.getKey());
            boolean heardFresh = now - track.heardAt <= HEARD_FRESH_TICKS;
            if (seen || heardFresh) {
                Peer before = peer(track);
                boolean recognizedNow = false;
                // Ears carry position but not the visual reads: a heard-only track keeps
                // what the SOUND said (occupation and legs) until the ear or the eyes say
                // otherwise. No "at_crafting" through the back of her head, no gaze, no crouch.
                track.last = seen ? fresh : heardFacts(fresh, track.last);
                track.awareness = seen ? Peer.Awareness.SEEN : Peer.Awareness.HEARD;
                track.lastLiveAt = now;
                track.nextCheckAt = now + interval(fresh.distance());
                if (seen) {
                    track.activityAt = now; // a live look re-witnesses whatever they're doing
                    if (!track.identified) {
                        track.identified = true; 
                        recognizedNow = true;
                        pending.add(PeerEvent.recognized(peer(track)));
                    }
                }
                if (!recognizedNow) {
                    announceIfChanged(track, before); // recognition already tells the new reading
                }
            } else {
                goDarkOrForget(track, now, it);
            }
        }
        decayActivities(now);
        List<PeerEvent> events = List.copyOf(pending);
        pending.clear();
        return events;
    }

    /**
     * Sound-told (and remembered) activities go stale: past {@link #heardActivityDecayTicks()}
     * without a fresh witness, "mining" fades to "just someone there". A SEEN track never decays —
     * its activity is re-witnessed on every attention beat.
     */
    private void decayActivities(long now) {
        for (Track track : tracks.values()) {
            if (track.awareness != Peer.Awareness.SEEN
                    && (track.last.activity() != Peer.Activity.IDLE
                            || track.last.locomotion() != Peer.Locomotion.STILL)
                    && now - track.activityAt > heardActivityDecayTicks()) {
                Peer before = peer(track);
                track.last = faded(track.last);
                track.activityAt = now;
                announceIfChanged(track, before);
            }
        }
    }

    /**
     * The ear's push channel: this body just made a sound she can hear (the mod's listener has
     * applied the hearing radius and vanilla's sneak-silence). Sound places its source, so a
     * never-seen someone is SPOTTED sight unseen and a remembered one snaps back without being
     * lost. Fresh vision outranks: a SEEN track only refreshes its heard-clock.
     */
    public void heard(PeerReading who, long now) {
        Track track = tracks.get(who.id());
        if (track == null) {
            track = new Track();
            track.last = heardFacts(who, who);
            track.awareness = Peer.Awareness.HEARD;
            track.lastLiveAt = now;
            track.heardAt = now;
            track.activityAt = now;
            track.nextCheckAt = now + interval(who.distance());
            tracks.put(who.id(), track);
            pending.add(PeerEvent.spotted(peer(track)));
            return;
        }
        track.heardAt = now;
        track.lastLiveAt = now;
        if (track.awareness != Peer.Awareness.SEEN) {
            Peer before = peer(track);
            track.last = heardFacts(who, who);
            track.awareness = Peer.Awareness.HEARD;
            track.activityAt = now; // the sound itself is the witness
            announceIfChanged(track, before);
        }
    }

    /** Everyone currently perceived — live readings first-class, remembered ones frozen. */
    public List<Peer> peers() {
        List<Peer> peers = new ArrayList<>(tracks.size());
        for (Track track : tracks.values()) {
            peers.add(peer(track));
        }
        return List.copyOf(peers);
    }

    // --- internals -------------------------------------------------------------------------------

    /** All channels dark: freeze as remembered, or (linger spent) forget and say so. */
    private void goDarkOrForget(Track track, long now, Iterator<Map.Entry<PersonId, Track>> it) {
        if (now - track.lastLiveAt > lingerTicks()) {
            track.awareness = Peer.Awareness.REMEMBERED;
            pending.add(PeerEvent.lost(peer(track)));
            it.remove();
            return;
        }
        Peer before = peer(track);
        track.awareness = Peer.Awareness.REMEMBERED;
        track.nextCheckAt = now + interval(track.last.distance());
        announceIfChanged(track, before); // the slip-out-of-sight moment, narrated once
    }

    /** The narrator's rule: if any rendered axis of the reading flipped, say so — once. */
    private void announceIfChanged(Track track, Peer before) {
        Peer after = peer(track);
        if (before.activity() != after.activity()
                || before.locomotion() != after.locomotion()
                || before.sneaking() != after.sneaking()
                || before.watching() != after.watching()
                || before.aimedAt() != after.aimedAt()
                || before.awareness() != after.awareness()) {
            pending.add(PeerEvent.readingChanged(after, before));
        }
    }

    /**
     * The attention curve: re-check interval lerped from {@link #nearIntervalTicks()} at
     * point-blank to {@link #farIntervalTicks()} at notice range.
     */
    private static long interval(double distance) {
        double t = Math.min(1.0, Math.max(0.0, distance / radius()));
        return Math.max(1, Math.round(nearIntervalTicks() + (farIntervalTicks() - nearIntervalTicks()) * t));
    }

    /**
     * Vertical field half-angle, relative to gaze pitch: human vision is wide across
     * ({@link #coneDegrees()}) and much narrower up-down. One circular cone at 200° would
     * include the zenith — an omniscience hole, just rotated.
     */
    private static final double VERTICAL_HALF_DEGREES = 60.0;

    /**
     * The view volume: the horizontal cone (yaw against half of {@link #coneDegrees()}) PLUS a
     * ±{@link #verticalHalfDegrees()} elevation band around gaze pitch. Someone within arm's
     * touch is trivially in view. Minecraft convention: yaw 0° = +Z, pitch −90° = straight up.
     */
    private static boolean inCone(Pos feet, double yawDegrees, double pitchDegrees, Pos target) {
        double dx = target.x() - feet.x();
        double dy = target.y() - feet.y();
        double dz = target.z() - feet.z();
        if (dx * dx + dy * dy + dz * dz < 2.25) {
            return true; // within touch — no meaningful bearing, and unmissable regardless
        }
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        double elevation = Math.toDegrees(Math.atan2(dy, horizontal));
        if (Math.abs(elevation + pitchDegrees) > VERTICAL_HALF_DEGREES) {
            return false; // above or below the band (MC pitch is positive-down, hence the +)
        }
        if (horizontal < 0.01) {
            return true; // straight up/down passed the band; there is no bearing left to test
        }
        double yaw = Math.toRadians(yawDegrees);
        double dot = (-Math.sin(yaw) * dx + Math.cos(yaw) * dz) / horizontal;
        return dot >= Math.cos(Math.toRadians(coneDegrees() / 2.0));
    }

    private static Peer peer(Track track) {
        PeerReading r = track.last;
        return new Peer(r.id(), r.name(), r.pos(), r.distance(), r.activity(), r.locomotion(),
                r.sneaking(), r.watching(), r.aimedAt(), track.identified, track.awareness);
    }

    /**
     * What a SOUND can carry: place, doing and moving feet — never gaze or posture. Place comes
     * from {@code placed}, occupation and legs from {@code told}.
     */
    private static PeerReading heardFacts(PeerReading placed, PeerReading told) {
        return new PeerReading(placed.id(), placed.name(), placed.pos(), placed.distance(),
                told.locomotion(), false, false, false, told.activity());
    }

    /** The shape a stale reading collapses to: just someone there — all axes faded. */
    private static PeerReading faded(PeerReading last) {
        return new PeerReading(last.id(), last.name(), last.pos(), last.distance(),
                Peer.Locomotion.STILL, false, false, false, Peer.Activity.IDLE);
    }
}
