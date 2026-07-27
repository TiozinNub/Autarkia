package dev.luizloyola.autarkia.core.brain.sense;

import dev.luizloyola.autarkia.core.config.Config;
import dev.luizloyola.autarkia.core.config.Knob;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The being sense, assembled — one per person, pure core; the peer sensor widened to every living
 * body (spec: {@code 2026-07-27-being-sense-design.md}). EYES (view cone plus line-of-sight rays),
 * EARS (the push {@link #heard} channel), ATTENTION (re-check cadence lerped with proximity),
 * OBJECT PERMANENCE (a 15s linger on every channel). Persons get the full classifier reading;
 * everything else a thin tier-0 one plus its kind's reader.
 *
 * <p>The identification ladder masks the reading: below {@link Being.Identified#SPECIES} a track
 * exposes {@link Being.Kind#UNKNOWN} and no species; a voice upgrades to SPECIES, sight to
 * INDIVIDUAL. Tiers never go back down, and every upgrade emits
 * {@link BeingEvent.Type#RECOGNIZED}.
 *
 * <p>{@link #HERD_MIN}+ live same-species herd animals within {@link #herdLinkRadius()} of each
 * other read out as one being under a stable minted id, their own events suppressed; 1–2 stay
 * individuals.
 *
 * <p>Rays are budgeted, scaling with the backlog: at most
 * {@code max(peers.ray_budget, ⌈work/4⌉)} a tick — over-budget re-checks defer a tick,
 * discoveries stay queued — so a 100-mob wave is noticed within ~4 ticks.
 */
public final class BeingSensorCore {
    /** Ticks between candidate sweeps — discovery cadence; monitored beings have their own. */
    public static final int SWEEP_INTERVAL_TICKS = 10;
    /** How long after a sound its source still counts as a live (heard) channel. */
    public static final int HEARD_FRESH_TICKS = 20;
    /** From this many same-species herd animals up, the perception collapses into a herd. */
    public static final int HERD_MIN = 3;

    /** Ticks a distance anchor must age before the approach trend re-measures — noise floor. */
    private static final int APPROACH_WINDOW_TICKS = 4;
    /** Blocks-per-tick of closing speed that reads as "moving towards me" (a chasing zombie
     *  closes at ~0.2); once on, the flag holds until the trend actually stops closing —
     *  hysteresis so an orbiting mob doesn't flicker events. */
    private static final double APPROACH_ON_SPEED = 0.04;

    // Half-range sentinel: "never" must survive (now - stamp) without overflowing.
    private static final long NEVER = Long.MIN_VALUE / 2;

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

    /** @see Knob#PEERS_RAY_BUDGET */
    public static int rayBudgetBase() {
        return Config.get().i(Knob.PEERS_RAY_BUDGET);
    }

    /** @see Knob#PEERS_HERD_LINK_RADIUS */
    public static int herdLinkRadius() {
        return Config.get().i(Knob.PEERS_HERD_LINK_RADIUS);
    }

    /**
     * Vertical field half-angle, relative to gaze pitch. Human-shaped vision: wide across
     * ({@link #coneDegrees()} horizontally), much narrower up-down.
     *
     * @see Knob#PEERS_VERTICAL_DEGREES
     */
    public static int verticalHalfDegrees() {
        return Config.get().i(Knob.PEERS_VERTICAL_DEGREES);
    }

    /** One perceived body: the latest reading, which channel carries it, and when it's due. */
    private static final class Track {
        BeingReading last;
        Being.Awareness awareness;
        /** The ladder rung actually achieved — the mask everything downstream reads through. */
        Being.Identified tier = Being.Identified.NONE;
        long nextCheckAt;
        long lastLiveAt;
        long heardAt = NEVER;
        /** When {@link #last}'s ACTIVITY was actually witnessed (seen live, or told by a
         *  sound) — sound-told activities decay against this; see {@code decayActivities}. */
        long activityAt = NEVER;
        /** The approach trend's distance anchor (NaN = not yet measured). */
        double trendDistance = Double.NaN;
        long trendAt;
        boolean approaching;
        /** The herd currently absorbing this body — null when reading out individually. */
        BeingId herd;
    }

    /** One herd aggregate: a stable id over a churning member set. */
    private static final class Herd {
        final BeingId id;
        final String species;
        List<BeingId> members = new ArrayList<>();
        Being lastView;

        Herd(BeingId id, String species) {
            this.id = id;
            this.species = species;
        }
    }

    private final Map<BeingId, Track> tracks = new LinkedHashMap<>();
    private final Map<BeingId, Herd> herds = new LinkedHashMap<>();
    /** Sweep-found candidates awaiting their (budgeted) first look, oldest first. */
    private final ArrayDeque<BeingReading> pendingDiscovery = new ArrayDeque<>();
    private final Set<BeingId> pendingIds = new HashSet<>();
    private final List<BeingEvent> pending = new ArrayList<>();
    private long lastSweepAt = NEVER;

    /**
     * One perception tick: sweep on the discovery cadence, re-check whoever is due (rays metered),
     * regroup herds, age the dark ones toward forgetting. Returns this tick's events — empty on
     * the common quiet tick.
     */
    public List<BeingEvent> tick(Pos feet, double yawDegrees, double pitchDegrees, long now,
                                 BeingWorld world) {
        boolean sweepBeat = now - lastSweepAt >= SWEEP_INTERVAL_TICKS;
        if (sweepBeat) {
            lastSweepAt = now;
            for (BeingReading candidate : world.candidates()) {
                if (!tracks.containsKey(candidate.id()) && pendingIds.add(candidate.id())) {
                    pendingDiscovery.addLast(candidate);
                }
            }
        }
        int due = countDue(now);
        // The scaled budget: never below the base, never letting a backlog take more than ~4
        // ticks to drain — 100 new mobs must not go unnoticed for seconds.
        int budget = Math.max(rayBudgetBase(),
                (due + pendingDiscovery.size() + 3) / 4);
        budget = recheckTracked(feet, yawDegrees, pitchDegrees, now, world, budget);
        discover(feet, yawDegrees, pitchDegrees, now, world, budget);
        if (sweepBeat) {
            regroupHerds(now);
        }
        decayActivities(now);
        List<BeingEvent> events = List.copyOf(pending);
        pending.clear();
        return events;
    }

    /**
     * The ear's push channel: this body just made a sound they can hear (the mod's listener has
     * already applied the hearing radius and vanilla's sneak-silence). Sound places its source, so
     * the reading is live — an unheard-of something is SPOTTED sight unseen, a remembered one
     * snaps back without having been lost; a fresh SEEN track only refreshes its heard-clock.
     *
     * <p>{@code voice} is whether the sound NAMES its maker's species: false for steps and other
     * incidental noise, true for an idle call, a hurt sound or a projectile launch. Voices never
     * name the INDIVIDUAL.
     */
    public void heard(BeingReading who, long now, boolean voice) {
        Track track = tracks.get(who.id());
        if (track == null) {
            track = new Track();
            track.last = heardFacts(who, who);
            track.awareness = Being.Awareness.HEARD;
            track.tier = voice ? Being.Identified.SPECIES : Being.Identified.NONE;
            track.lastLiveAt = now;
            track.heardAt = now;
            track.activityAt = now;
            track.nextCheckAt = now + interval(who.distance());
            tracks.put(who.id(), track);
            if (track.herd == null) {
                pending.add(BeingEvent.spotted(being(track)));
            }
            return;
        }
        track.heardAt = now;
        track.lastLiveAt = now;
        updateTrend(track, who.distance(), now);
        Being before = being(track);
        boolean recognizedNow = false;
        if (voice && track.tier == Being.Identified.NONE) {
            track.tier = Being.Identified.SPECIES; // the call named the something
            recognizedNow = true;
        }
        if (track.awareness != Being.Awareness.SEEN) {
            track.last = heardFacts(who, track.last);
            track.awareness = Being.Awareness.HEARD;
            track.activityAt = now; // the sound itself is the witness
        }
        if (track.herd != null) {
            return; // absorbed: the herd speaks for its members
        }
        if (recognizedNow) {
            pending.add(BeingEvent.recognized(being(track), before));
        } else {
            announceIfChanged(track, before);
        }
    }

    /**
     * Everything currently perceived: individually-read tracks first-class, plus one being per
     * herd — live readings and remembered ones alike, masked to their achieved tier.
     */
    public List<Being> beings() {
        List<Being> out = new ArrayList<>(tracks.size());
        for (Track track : tracks.values()) {
            if (track.herd == null) {
                out.add(being(track));
            }
        }
        for (Herd herd : herds.values()) {
            out.add(herdBeing(herd));
        }
        return List.copyOf(out);
    }

    // --- the attention loop ----------------------------------------------------------------

    private int countDue(long now) {
        int due = 0;
        for (Track track : tracks.values()) {
            if (track.nextCheckAt <= now) {
                due++;
            }
        }
        return due;
    }

    /** Re-checks every due track, spending sight checks until the budget runs dry. */
    private int recheckTracked(Pos feet, double yawDegrees, double pitchDegrees, long now,
                               BeingWorld world, int budget) {
        for (Iterator<Map.Entry<BeingId, Track>> it = tracks.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<BeingId, Track> entry = it.next();
            Track track = entry.getValue();
            if (track.nextCheckAt > now) {
                continue;
            }
            BeingReading fresh = world.reading(entry.getKey());
            if (fresh == null) {
                goDarkOrForget(track, entry.getKey(), now, it);
                continue;
            }
            boolean inCone = inCone(feet, yawDegrees, pitchDegrees, fresh.pos());
            if (inCone && budget <= 0) {
                track.nextCheckAt = now + 1; // budget spent — defer, never skip
                continue;
            }
            boolean seen = false;
            if (inCone) {
                budget--;
                seen = world.inSight(entry.getKey());
            }
            boolean heardFresh = now - track.heardAt <= HEARD_FRESH_TICKS;
            if (seen || heardFresh) {
                Being before = being(track);
                boolean recognizedNow = false;
                updateTrend(track, fresh.distance(), now);
                // Ears carry position (sound places its source) but not the visual reads: no
                // gaze, no crouch, no gear through the back of a wall.
                track.last = seen ? fresh : heardFacts(fresh, track.last);
                track.awareness = seen ? Being.Awareness.SEEN : Being.Awareness.HEARD;
                track.lastLiveAt = now;
                track.nextCheckAt = now + interval(fresh.distance());
                if (seen) {
                    track.activityAt = now; // a live look re-witnesses whatever they're doing
                    if (track.tier != Being.Identified.INDIVIDUAL) {
                        track.tier = Being.Identified.INDIVIDUAL; // sight tells everything
                        recognizedNow = true;
                    }
                }
                if (track.herd != null) {
                    continue; // absorbed: the herd speaks for its members
                }
                if (recognizedNow) {
                    pending.add(BeingEvent.recognized(being(track), before));
                } else {
                    announceIfChanged(track, before);
                }
            } else {
                goDarkOrForget(track, entry.getKey(), now, it);
            }
        }
        return budget;
    }

    /** Gives queued candidates their first look, cheapest test first, until the budget dries. */
    private void discover(Pos feet, double yawDegrees, double pitchDegrees, long now,
                          BeingWorld world, int budget) {
        while (!pendingDiscovery.isEmpty() && budget > 0) {
            BeingReading candidate = pendingDiscovery.pollFirst();
            pendingIds.remove(candidate.id());
            if (tracks.containsKey(candidate.id())) {
                continue; // the ear beat the eyes to it
            }
            if (!inCone(feet, yawDegrees, pitchDegrees, candidate.pos())) {
                continue; // outside the cone costs nothing — next sweep may re-offer them
            }
            budget--;
            if (!world.inSight(candidate.id())) {
                continue;
            }
            Track track = new Track();
            track.last = candidate;
            track.awareness = Being.Awareness.SEEN;
            track.tier = Being.Identified.INDIVIDUAL;
            track.lastLiveAt = now;
            track.activityAt = now;
            track.nextCheckAt = now + interval(candidate.distance());
            tracks.put(candidate.id(), track);
            pending.add(BeingEvent.spotted(being(track)));
        }
    }

    // --- herds ------------------------------------------------------------------------------

    /**
     * Recomputes the herd grouping: single-linkage clusters per species within
     * {@link #herdLinkRadius()}, {@link #HERD_MIN}+ members collapsing under a stable id (adopted
     * from an overlapping herd, else minted). Sweep beats only — membership holds between beats.
     */
    private void regroupHerds(long now) {
        List<Map.Entry<BeingId, Track>> herdable = new ArrayList<>();
        for (Map.Entry<BeingId, Track> entry : tracks.entrySet()) {
            if (entry.getValue().last.herdAnimal()) {
                herdable.add(entry);
            }
        }
        List<List<Map.Entry<BeingId, Track>>> clusters = cluster(herdable);
        Map<BeingId, Herd> next = new LinkedHashMap<>();
        Set<BeingId> grouped = new HashSet<>();
        for (List<Map.Entry<BeingId, Track>> members : clusters) {
            if (members.size() < HERD_MIN) {
                continue;
            }
            Herd herd = adoptOrMint(members);
            herd.members = new ArrayList<>(members.size());
            for (Map.Entry<BeingId, Track> member : members) {
                herd.members.add(member.getKey());
                member.getValue().herd = herd.id;
                grouped.add(member.getKey());
            }
            next.put(herd.id, herd);
        }
        for (Map.Entry<BeingId, Track> entry : tracks.entrySet()) {
            if (!grouped.contains(entry.getKey())) {
                entry.getValue().herd = null; // loners and dissolved herds read out again
            }
        }
        for (Herd gone : herds.values()) {
            if (!next.containsKey(gone.id) && gone.lastView != null && membersAllGone(gone)) {
                pending.add(BeingEvent.lost(gone.lastView));
            }
            // Dissolved-but-present members resume individually, silently: they were never lost.
        }
        for (Herd herd : next.values()) {
            Being view = herdBeing(herd);
            Herd previous = herds.get(herd.id);
            if (previous == null) {
                pending.add(BeingEvent.spotted(view));
            } else if (previous.lastView != null
                    && previous.lastView.count() != view.count()) {
                pending.add(BeingEvent.readingChanged(view, previous.lastView));
            }
            herd.lastView = view;
        }
        herds.clear();
        herds.putAll(next);
    }

    private boolean membersAllGone(Herd herd) {
        for (BeingId member : herd.members) {
            if (tracks.containsKey(member)) {
                return false;
            }
        }
        return true;
    }

    /** The stable-id rule: a cluster keeps the id of whichever herd it shares a member with. */
    private Herd adoptOrMint(List<Map.Entry<BeingId, Track>> members) {
        for (Map.Entry<BeingId, Track> member : members) {
            BeingId previous = member.getValue().herd;
            if (previous != null) {
                Herd herd = herds.get(previous);
                if (herd != null && herd.species.equals(member.getValue().last.species())) {
                    return herd;
                }
            }
        }
        return new Herd(BeingId.of(UUID.randomUUID()),
                members.get(0).getValue().last.species());
    }

    /** Single-linkage clustering by species over last-known positions — small-N. */
    private static List<List<Map.Entry<BeingId, Track>>> cluster(
            List<Map.Entry<BeingId, Track>> herdable) {
        List<List<Map.Entry<BeingId, Track>>> clusters = new ArrayList<>();
        List<Map.Entry<BeingId, Track>> left = new ArrayList<>(herdable);
        while (!left.isEmpty()) {
            List<Map.Entry<BeingId, Track>> cluster = new ArrayList<>();
            cluster.add(left.remove(left.size() - 1));
            boolean grew = true;
            while (grew) {
                grew = false;
                for (Iterator<Map.Entry<BeingId, Track>> it = left.iterator(); it.hasNext(); ) {
                    Map.Entry<BeingId, Track> other = it.next();
                    if (linksToAny(other, cluster)) {
                        cluster.add(other);
                        it.remove();
                        grew = true;
                    }
                }
            }
            clusters.add(cluster);
        }
        return clusters;
    }

    private static boolean linksToAny(Map.Entry<BeingId, Track> candidate,
                                      List<Map.Entry<BeingId, Track>> cluster) {
        BeingReading a = candidate.getValue().last;
        int link = herdLinkRadius();
        for (Map.Entry<BeingId, Track> member : cluster) {
            BeingReading b = member.getValue().last;
            if (a.species().equals(b.species())
                    && Math.abs(a.pos().x() - b.pos().x()) <= link
                    && Math.abs(a.pos().y() - b.pos().y()) <= link
                    && Math.abs(a.pos().z() - b.pos().z()) <= link) {
                return true;
            }
        }
        return false;
    }

    /** The herd's one reading: centroid, head count, nearest edge, best member channel. */
    private Being herdBeing(Herd herd) {
        long x = 0;
        long y = 0;
        long z = 0;
        int count = 0;
        double nearest = Double.MAX_VALUE;
        Being.Awareness best = Being.Awareness.REMEMBERED;
        for (BeingId id : herd.members) {
            Track track = tracks.get(id);
            if (track == null) {
                continue;
            }
            count++;
            x += track.last.pos().x();
            y += track.last.pos().y();
            z += track.last.pos().z();
            nearest = Math.min(nearest, track.last.distance());
            if (track.awareness == Being.Awareness.SEEN) {
                best = Being.Awareness.SEEN;
            } else if (track.awareness == Being.Awareness.HEARD
                    && best == Being.Awareness.REMEMBERED) {
                best = Being.Awareness.HEARD;
            }
        }
        if (count == 0) {
            return herd.lastView; // only reachable transiently between expiry and regroup
        }
        Pos centroid = new Pos((int) (x / count), (int) (y / count), (int) (z / count));
        int spread = 0;
        for (BeingId id : herd.members) {
            Track track = tracks.get(id);
            if (track != null) {
                Pos at = track.last.pos();
                spread = Math.max(spread, Math.max(Math.abs(at.x() - centroid.x()),
                        Math.abs(at.z() - centroid.z())));
            }
        }
        List<BeingId> live = new ArrayList<>(herd.members.size());
        for (BeingId id : herd.members) {
            if (tracks.containsKey(id)) {
                live.add(id);
            }
        }
        return new Being(herd.id, Being.Kind.PASSIVE, herd.species, "", null, centroid,
                nearest, count, spread, true, live, Being.Activity.IDLE,
                Being.Locomotion.STILL, false, false, false, false, false, Being.Gear.NONE,
                Being.Identified.INDIVIDUAL, best);
    }

    // --- internals --------------------------------------------------------------------------

    /** All channels dark: freeze as remembered, or (linger spent) forget and say so. */
    private void goDarkOrForget(Track track, BeingId id, long now,
                                Iterator<Map.Entry<BeingId, Track>> it) {
        if (now - track.lastLiveAt > lingerTicks()) {
            track.awareness = Being.Awareness.REMEMBERED;
            if (track.herd == null) {
                pending.add(BeingEvent.lost(being(track)));
            }
            it.remove();
            return;
        }
        Being before = being(track);
        track.awareness = Being.Awareness.REMEMBERED;
        track.nextCheckAt = now + interval(track.last.distance());
        if (track.herd == null) {
            announceIfChanged(track, before); // the slip-out-of-sight moment, narrated once
        }
    }

    /**
     * Sound-told (and remembered) activities go stale: past {@link #heardActivityDecayTicks()}
     * without a fresh witness, "mining" fades to "just someone there". A SEEN track never
     * decays: its activity is re-witnessed on every attention beat.
     */
    private void decayActivities(long now) {
        for (Track track : tracks.values()) {
            if (track.awareness != Being.Awareness.SEEN
                    && (track.last.activity() != Being.Activity.IDLE
                            || track.last.locomotion() != Being.Locomotion.STILL)
                    && now - track.activityAt > heardActivityDecayTicks()) {
                Being before = being(track);
                track.last = faded(track.last);
                track.activityAt = now;
                if (track.herd == null) {
                    announceIfChanged(track, before);
                }
            }
        }
    }

    /**
     * The approach trend: closing distance over time means approaching, so possibly targeting us.
     * Windowed like the movement classifier (an irregular cadence would alias a raw delta), with
     * an on-threshold and a stop-closing release so an orbiting mob doesn't flicker.
     */
    private static void updateTrend(Track track, double distance, long now) {
        if (Double.isNaN(track.trendDistance)) {
            track.trendDistance = distance;
            track.trendAt = now;
            return;
        }
        long dt = now - track.trendAt;
        if (dt < APPROACH_WINDOW_TICKS) {
            return;
        }
        double closing = (track.trendDistance - distance) / dt;
        track.approaching = closing >= APPROACH_ON_SPEED || (track.approaching && closing > 0.0);
        track.trendDistance = distance;
        track.trendAt = now;
    }

    /** The narrator's rule: if any rendered axis of the MASKED reading flipped, say so — once. */
    private void announceIfChanged(Track track, Being before) {
        Being after = being(track);
        if (before.activity() != after.activity()
                || before.locomotion() != after.locomotion()
                || before.sneaking() != after.sneaking()
                || before.watching() != after.watching()
                || before.aimedAt() != after.aimedAt()
                || before.approaching() != after.approaching()
                || before.awareness() != after.awareness()) {
            pending.add(BeingEvent.readingChanged(after, before));
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
        if (Math.abs(elevation + pitchDegrees) > verticalHalfDegrees()) {
            return false; // above or below the band (MC pitch is positive-down, hence the +)
        }
        if (horizontal < 0.01) {
            return true; // straight up/down passed the band; there is no bearing left to test
        }
        double yaw = Math.toRadians(yawDegrees);
        double dot = (-Math.sin(yaw) * dx + Math.cos(yaw) * dz) / horizontal;
        return dot >= Math.cos(Math.toRadians(coneDegrees() / 2.0));
    }

    /**
     * A track rendered as one {@link Being}, MASKED to its achieved tier: below SPECIES even the
     * kind is unknown, below INDIVIDUAL the name, profession and gear stay hidden. Approach shows
     * only on an identified aggressive body.
     */
    private Being being(Track track) {
        BeingReading r = track.last;
        Being.Identified tier = track.tier;
        boolean speciesKnown = tier != Being.Identified.NONE;
        boolean seen = tier == Being.Identified.INDIVIDUAL;
        boolean aggressive = speciesKnown && r.aggressive();
        return new Being(r.id(),
                speciesKnown ? r.kind() : Being.Kind.UNKNOWN,
                speciesKnown ? r.species() : "",
                seen ? r.name() : "",
                seen ? r.profession() : null,
                r.pos(), r.distance(), 1, 0,
                speciesKnown && r.herdAnimal(), List.of(),
                r.activity(), r.locomotion(), r.sneaking(), r.watching(), r.aimedAt(),
                track.approaching && aggressive, aggressive,
                seen ? r.gear() : Being.Gear.NONE,
                tier, track.awareness);
    }

    /**
     * What a SOUND can carry: place, doing, and moving feet — never gaze, posture, or gear
     * (eyes-only reads). Place comes from {@code placed} (the latest position fix); occupation
     * and legs come from {@code told} (the last thing a sound actually said).
     */
    private static BeingReading heardFacts(BeingReading placed, BeingReading told) {
        return new BeingReading(placed.id(), placed.kind(), placed.species(), placed.name(),
                placed.profession(), placed.herdAnimal(), placed.pos(), placed.distance(),
                told.locomotion(), false, false, false, placed.aggressive(), placed.gear(),
                told.activity());
    }

    /** The shape a stale reading collapses to: just someone there — all axes faded. */
    private static BeingReading faded(BeingReading last) {
        return new BeingReading(last.id(), last.kind(), last.species(), last.name(),
                last.profession(), last.herdAnimal(), last.pos(), last.distance(),
                Being.Locomotion.STILL, false, false, false, last.aggressive(), last.gear(),
                Being.Activity.IDLE);
    }
}
