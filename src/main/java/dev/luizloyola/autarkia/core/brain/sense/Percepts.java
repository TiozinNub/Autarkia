package dev.luizloyola.autarkia.core.brain.sense;

import dev.luizloyola.autarkia.core.brain.knowledge.BlockProbe;
import dev.luizloyola.autarkia.core.inv.Inventory;
import dev.luizloyola.autarkia.core.person.Needs;
import java.util.List;

/**
 * The perception bundle — the sense half of {@link dev.luizloyola.autarkia.core.brain.BrainContext},
 * mirroring {@link dev.luizloyola.autarkia.core.brain.act.ActuatorAccess}: core names what an NPC
 * can perceive, the mod {@code BrainDriver} assembles it over live entity state, one accessor per
 * sense domain. These are LIVE views, not snapshots — a method scoring applicability and a task
 * acting a tick later both see current truth.
 */
public interface Percepts {
    /**
     * The carried 41 slots ({@code core/inv}) — live, the same object the equipment mirror
     * maintains. The brain may write here; the mirror pushes changes onto the entity.
     */
    Inventory inventory();

    /**
     * Body vitals ({@code core/person}) — read-only BY CONVENTION: the body owns and ticks its
     * metabolism, and nutrition is applied by the body when the consume actuator finishes, never
     * by a task writing here. The brain only reads pressure ({@code hunger()}, {@code band()}).
     */
    Needs needs();

    /** What is edible and what eating it does — see {@link FoodLookup}. */
    FoodLookup foods();

    /**
     * Where the body stands — the feet cell, in whole blocks (the pathfinder/Navigator grid). Read
     * live, so a target offset from it ({@code WanderStep}) is never offset from a stale spawn
     * point.
     */
    Pos position();

    /**
     * Nearby hostiles, from the mod's budgeted entity queries. Nearest-first is not guaranteed:
     * a consumer that cares about ordering must compute it from {@link Threat#distance()}. An
     * empty list means no danger in range — the only signal {@code FleeInstinct} needs.
     */
    List<Threat> threats();

    /**
     * The world's blocks, seen through the one block vocabulary ({@code BlockKind}) — the same
     * probe perception's sensor reads through, now offered to tasks for their task-time re-walks
     * (a chop re-scanning a remembered grove: memory points, world is truth). Live reads, server
     * side; use them budgeted the way the sensor does — this is the expensive sense class.
     */
    BlockProbe blocks();

    /**
     * Nearby dropped items, sensed right now — budgeted and briefly cached. Bare sightings (cell
     * + item id); ground-vs-stranded and mine-vs-noise are the consumer's questions.
     */
    List<Drop> drops();

    /**
     * Nearby people, sensed right now — other Persons and live players, one list, deliberately
     * indistinguishable (see {@link Peer}). Budgeted and briefly cached like {@link #threats()};
     * nearest-first is not guaranteed.
     */
    List<Peer> peers();

    /**
     * The current game time in ticks — the same clock knowledge timestamps carry, so staleness
     * pricing ({@code memory.age(time())}) compares like with like.
     */
    long time();
}
