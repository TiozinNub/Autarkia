package dev.luizloyola.autarkia.core.brain.knowledge;

import dev.luizloyola.autarkia.core.brain.sense.Being;
import dev.luizloyola.autarkia.core.brain.sense.Pos;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes what the being sense perceives of herd animals into a person's knowledge — the
 * {@link PoiKind#HERD} producer, pure logic over {@link Being} sightings (spec:
 * {@code 2026-07-27-being-sense-design.md}). Runs on a slow beat: the sense holds live truth, this
 * needs only the durable "cows live around there".
 *
 * <p>3+ head of one species is a HERD memory (anchor = centroid, units = head count); 1–2 are
 * INDIVIDUAL memories of the same kind, so the brain can weigh two lone cows against a herd of six.
 * A wandered herd is re-acquired by EXPAND-RECENTER: inflate the remembered area 200–300%, look
 * again, recenter — which also merges outliers that rejoined. A forming herd CONSUMES loner
 * memories inside its area; a herd the observer stands over that has shrunk below three head
 * downgrades, its loners re-noted as individuals the same pass. Loners that left go by staleness.
 *
 * <p>Only LIVE, identified sightings write — {@link Being.Awareness#REMEMBERED} is already memory.
 * Re-sightings refresh silently; only new memories and downgrades are events, so a watched pasture
 * does not journal "noticed a herd" every beat.
 */
public final class HerdNoter {
    /** Ticks between noting beats — the mod's cadence; knowledge does not need tick truth. */
    public static final int NOTE_INTERVAL_TICKS = 100;
    /** The expand-recenter inflation over the remembered area (200–300% — decision: Luiz). */
    public static final double REACQUIRE_INFLATE = 2.5;
    /** Vertical slack added when inflating — herds drift over terrain, not through it. */
    private static final int INFLATE_Y = 8;
    /** How close (Chebyshev) the observer must stand to a remembered herd anchor for "those
     *  mobs are not here" to count as witnessed absence rather than a wall in the way. */
    public static final int ABSENCE_RADIUS = 12;

    private HerdNoter() {
    }

    /**
     * One noting beat: fold the current herd-animal sightings into knowledge. Returns the
     * events worth narrating (new memories, downgrades) — empty on the common nothing-new beat.
     */
    public static List<SenseEvent> note(Pos observer, List<Being> beings,
                                        PersonKnowledge knowledge, long now) {
        List<SenseEvent> events = new ArrayList<>();
        List<Being> herds = new ArrayList<>();
        List<Being> loners = new ArrayList<>();
        for (Being being : beings) {
            if (!being.herdAnimal() || being.awareness() == Being.Awareness.REMEMBERED
                    || being.identified() == Being.Identified.NONE) {
                continue;
            }
            (being.herd() ? herds : loners).add(being);
        }
        downgradeAbsent(observer, beings, knowledge, now, events);
        for (Being herd : herds) {
            noteHerd(herd, knowledge, now, events);
        }
        for (Being loner : loners) {
            noteLoner(loner, knowledge, now, events);
        }
        return events;
    }

    /**
     * The herd rule: expand-recenter over any remembered HERD area that reaches the new centroid,
     * plus IDENTITY consumption of loner memories — the herd carries its members' ids, so exactly
     * their memories retire with it and a stray grazing nearby keeps its own (area consumption ate
     * one, caught by test). A new herd is a NOTED event.
     */
    private static void noteHerd(Being herd, PersonKnowledge knowledge, long now,
                                 List<SenseEvent> events) {
        int spread = Math.max(2, herd.spread());
        Pos centroid = herd.pos();
        Region bounds = new Region(
                new Pos(centroid.x() - spread, centroid.y() - 2, centroid.z() - spread),
                new Pos(centroid.x() + spread, centroid.y() + 2, centroid.z() + spread));
        PoiMemory memory = new PoiMemory(PoiKind.HERD, herd.species(), centroid, bounds,
                herd.count(), false, now);
        boolean knownAlready = false;
        for (PoiMemory existing : List.copyOf(knowledge.all(PoiKind.HERD))) {
            if (!existing.detail().equals(memory.detail())) {
                continue;
            }
            boolean absorbed;
            if (existing.units() >= 3) {
                absorbed = inflated(existing.bounds()).contains(centroid);
                knownAlready |= absorbed;
            } else {
                absorbed = existing.individual() != null
                        && herd.members().stream()
                                .anyMatch(member -> member.value().equals(existing.individual()));
            }
            if (absorbed) {
                knowledge.forget(PoiKind.HERD, existing.anchor());
            }
        }
        knowledge.note(memory);
        if (!knownAlready) {
            events.add(SenseEvent.noted(memory));
        }
    }

    /**
     * The loner rule: an individual memory per animal, KEYED BY the ANIMAL — the sighting's
     * {@code BeingId} is the entity UUID, so a re-sighted pig drags its one memory wherever it
     * wandered instead of shedding ghost pigs (proximity matching could not follow a stroll longer
     * than its radius; identity is consulted only for animals perception already delivered). A
     * same-species herd memory claiming this ground still wins: that pig is the herd's head count
     * now, and its own memory retires.
     */
    private static void noteLoner(Being loner, PersonKnowledge knowledge, long now,
                                  List<SenseEvent> events) {
        Pos at = loner.pos();
        boolean fresh = true;
        boolean herdGround = false;
        for (PoiMemory existing : List.copyOf(knowledge.all(PoiKind.HERD))) {
            if (!existing.detail().equals(loner.species())) {
                continue;
            }
            if (existing.units() >= 3 && inflated(existing.bounds()).contains(at)) {
                herdGround = true; // the herd's ground — one memory speaks for its members
                continue;
            }
            if (loner.id().value().equals(existing.individual())) {
                knowledge.forget(PoiKind.HERD, existing.anchor()); // the memory follows the pig
                fresh = false;
            }
        }
        if (herdGround) {
            return; // absorbed — and any old memory of this individual retired above
        }
        PoiMemory memory = new PoiMemory(PoiKind.HERD, loner.species(), loner.id().value(),
                at, Region.of(at), 1, false, now);
        knowledge.note(memory);
        if (fresh) {
            events.add(SenseEvent.noted(memory));
        }
    }

    /**
     * The witnessed-absence rules, both walked up to ({@link #ABSENCE_RADIUS}) — memories clear on
     * EVIDENCE, never on distance or doubt:
     *
     * <ul>
     *   <li><b>Herds downgrade on a SHRUNKEN sighting</b>: one or two head where three-plus were
     *       remembered forgets the herd memory; the loners present re-note as individuals later
     *       this pass. ZERO sightings do not downgrade — the cone may not cover the
     *       pasture, and a truly-gone herd ages out.</li>
     *   <li><b>A loner clears when its ANIMAL is provably not here</b>: standing over the spot with
     *       the individual absent from perception ENTIRELY forgets the memory, and identity makes
     *       that exact. A merely-REMEMBERED animal (the pillar case) still counts as perceived, and
     *       one perceived live elsewhere is left to the loner rule, which MOVES its memory.</li>
     * </ul>
     */
    private static void downgradeAbsent(Pos observer, List<Being> beings,
                                        PersonKnowledge knowledge, long now,
                                        List<SenseEvent> events) {
        for (PoiMemory existing : List.copyOf(knowledge.all(PoiKind.HERD))) {
            if (chebyshev(existing.anchor(), observer) > ABSENCE_RADIUS) {
                continue;
            }
            if (existing.units() >= 3) {
                Region area = inflated(existing.bounds());
                int present = 0;
                for (Being being : beings) {
                    if (being.herdAnimal() && being.awareness() != Being.Awareness.REMEMBERED
                            && being.identified() != Being.Identified.NONE
                            && being.species().equals(existing.detail())
                            && area.contains(being.pos())) {
                        present += being.count();
                    }
                }
                if (present > 0 && present < 3) {
                    knowledge.forget(PoiKind.HERD, existing.anchor());
                    events.add(SenseEvent.forgot(PoiKind.HERD, existing.anchor()));
                }
                continue;
            }
            if (existing.individual() == null) {
                continue; // a legacy loner with no identity — leave it to aging
            }
            boolean perceivedAtAll = false;
            for (Being being : beings) {
                if (being.id().value().equals(existing.individual())
                        || (being.herd() && being.members().stream()
                                .anyMatch(m -> m.value().equals(existing.individual())))) {
                    perceivedAtAll = true;
                    break;
                }
            }
            if (!perceivedAtAll) {
                knowledge.forget(PoiKind.HERD, existing.anchor());
                events.add(SenseEvent.forgot(PoiKind.HERD, existing.anchor()));
            }
        }
    }

    /** The remembered area, expanded {@link #REACQUIRE_INFLATE}× about its center. */
    static Region inflated(Region bounds) {
        int cx = (bounds.min().x() + bounds.max().x()) / 2;
        int cz = (bounds.min().z() + bounds.max().z()) / 2;
        int halfX = (int) Math.ceil((bounds.max().x() - bounds.min().x() + 1) * REACQUIRE_INFLATE / 2.0);
        int halfZ = (int) Math.ceil((bounds.max().z() - bounds.min().z() + 1) * REACQUIRE_INFLATE / 2.0);
        return new Region(
                new Pos(cx - halfX, bounds.min().y() - INFLATE_Y, cz - halfZ),
                new Pos(cx + halfX, bounds.max().y() + INFLATE_Y, cz + halfZ));
    }

    private static int chebyshev(Pos a, Pos b) {
        return Math.max(Math.abs(a.x() - b.x()),
                Math.max(Math.abs(a.y() - b.y()), Math.abs(a.z() - b.z())));
    }
}
