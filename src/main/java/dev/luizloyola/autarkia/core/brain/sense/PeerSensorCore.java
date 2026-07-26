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
    public List<PeerEvent> tick(Pos feet, double yawDegrees, long now, PeerWorld world) {
        if (now - lastSweepAt >= SWEEP_INTERVAL_TICKS) {
            lastSweepAt = now;
            for (PeerReading candidate : world.candidates()) {
                if (tracks.containsKey(candidate.id())) {
                    continue; // already perceived — their own cadence owns updates
                }
                if (inCone(feet, yawDegrees, candidate.pos()) && world.inSight(candidate.id())) {
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
            boolean seen = inCone(feet, yawDegrees, fresh.pos()) && world.inSight(entry.getKey());
            boolean heardFresh = now - track.heardAt <= HEARD_FRESH_TICKS;
            if (seen || heardFresh) {
                boolean wasLive = track.awareness != Peer.Awareness.REMEMBERED;
                Peer.Activity was = track.last.activity();
                // Ears carry position but not the visual reads: a heard-only track keeps
                // whatever the SOUND said until the ear or the eyes say otherwise — no
                // "at_crafting" through the back of her head, and no gaze or crouch either.
                track.last = seen ? fresh : heardFacts(fresh, was);
                track.awareness = seen ? Peer.Awareness.SEEN : Peer.Awareness.HEARD;
                track.lastLiveAt = now;
                track.nextCheckAt = now + interval(fresh.distance());
                if (seen) {
                    track.activityAt = now; // a live look re-witnesses whatever they're doing
                    if (!track.identified) {
                        track.identified = true; 
                        pending.add(PeerEvent.recognized(peer(track)));
                    }
                }
                if (wasLive && was != track.last.activity()) {
                    pending.add(PeerEvent.activityChanged(peer(track), was));
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
                    && track.last.activity() != Peer.Activity.IDLE
                    && now - track.activityAt > heardActivityDecayTicks()) {
                Peer.Activity was = track.last.activity();
                track.last = heardFacts(track.last, Peer.Activity.IDLE);
                track.activityAt = now;
                pending.add(PeerEvent.activityChanged(peer(track), was));
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
            track.last = heardFacts(who, who.activity());
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
            boolean wasLive = track.awareness == Peer.Awareness.HEARD;
            Peer.Activity was = track.last.activity();
            track.last = heardFacts(who, who.activity());
            track.awareness = Peer.Awareness.HEARD;
            track.activityAt = now; // the sound itself is the witness
            if (wasLive && was != who.activity()) {
                pending.add(PeerEvent.activityChanged(peer(track), was));
            }
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
        track.awareness = Peer.Awareness.REMEMBERED;
        track.nextCheckAt = now + interval(track.last.distance());
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
     * The horizontal view cone: the angle between her facing and the direction to the target,
     * against half of {@link #coneDegrees()}. Someone standing in her cell is trivially in view.
     * Yaw follows the Minecraft convention (0° = +Z, 90° = −X).
     */
    private static boolean inCone(Pos feet, double yawDegrees, Pos target) {
        double dx = target.x() - feet.x();
        double dz = target.z() - feet.z();
        double lengthSq = dx * dx + dz * dz;
        if (lengthSq < 1.0) {
            return true; 
        }
        double yaw = Math.toRadians(yawDegrees);
        double fx = -Math.sin(yaw);
        double fz = Math.cos(yaw);
        double length = Math.sqrt(lengthSq);
        double dot = (fx * dx + fz * dz) / length;
        return dot >= Math.cos(Math.toRadians(coneDegrees() / 2.0));
    }

    private static Peer peer(Track track) {
        PeerReading r = track.last;
        return new Peer(r.id(), r.name(), r.pos(), r.distance(), r.activity(), r.sneaking(),
                r.watching(), track.identified, track.awareness);
    }

    /**
     * What a SOUND can carry: place and doing — never gaze or posture. Also the shape a decayed
     * reading collapses to: just someone there.
     */
    private static PeerReading heardFacts(PeerReading fresh, Peer.Activity activity) {
        return new PeerReading(fresh.id(), fresh.name(), fresh.pos(), fresh.distance(),
                false, false, activity);
    }
}
