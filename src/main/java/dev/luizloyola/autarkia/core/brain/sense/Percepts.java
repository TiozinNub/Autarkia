package dev.luizloyola.autarkia.core.brain.sense;

import dev.luizloyola.autarkia.core.inv.Inventory;
import dev.luizloyola.autarkia.core.person.Needs;

/**
 * The perception bundle — the sense half of {@link dev.luizloyola.autarkia.core.brain.BrainContext},
 * mirroring {@link dev.luizloyola.autarkia.core.brain.act.ActuatorAccess} on the acting half:
 * core names what an NPC can perceive, the mod {@code BrainDriver} assembles it over live entity
 * state.
 *
 * <p>These are LIVE views, not snapshots — a method scoring its applicability and a task acting
 * a tick later both see the current truth.
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
}
