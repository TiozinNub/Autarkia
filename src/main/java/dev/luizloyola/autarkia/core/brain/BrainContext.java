package dev.luizloyola.autarkia.core.brain;

import dev.luizloyola.autarkia.core.brain.act.ActuatorAccess;
import dev.luizloyola.autarkia.core.brain.board.PersonClaims;
import dev.luizloyola.autarkia.core.brain.knowledge.PersonKnowledge;
import dev.luizloyola.autarkia.core.brain.sense.Percepts;
import dev.luizloyola.autarkia.core.log.PersonJournal;
import dev.luizloyola.autarkia.core.person.Gender;

/**
 * Everything the task machinery is handed per call: the body's controls ({@link #actuators()}) and
 * the brain's senses ({@link #percepts()}), bundled so one contract serves the whole tree — the
 * executor hands the same context to every call.
 *
 * <p>Assembled per Person by the mod {@code BrainDriver}: fresh views over live state, never a
 * snapshot. Passed into every call rather than held — a task owns intent, never the body.
 */
public interface BrainContext {
    /** The body's controls — one port per actuator domain (legs, gullet, ...). */
    ActuatorAccess actuators();

    Percepts percepts();

    /**
     * This person's debug journal ({@link PersonJournal}), written by tasks and instincts. One-way
     * (recording cannot affect the simulation), and bound to the {@code PersonId}, so an
     * offline-simulated person with no entity logs through the same call.
     */
    PersonJournal journal();

    /**
     * Whose brain this is — the one place a core task may get a pronoun from
     * ({@code ctx.gender().objectPronoun()}). Journal lines reach chat, so a hardcoded "her"
     * misgenders half the settlement, and asking here survives a third {@link Gender} value.
     */
    Gender gender();

    /**
     * This person's remembered POIs — memory, not perception: the same object the "notice as you
     * go" sensor fills, so a task's {@code forget(...)} is read by everything else. Methods price
     * staleness with {@code nearest(...)}/{@code age(...)}. {@code PersonId}-keyed, so it outlives
     * the entity.
     */
    PersonKnowledge knowledge();

    /**
     * The shared work-site claims through this person's eyes ({@link PersonClaims}): a method skips
     * sites that are not available, a working task heartbeats its site every tick and releases it
     * on every exit. Defaults to {@link PersonClaims#SOLO} — everything available, claims always
     * succeed — so a context with no shared registry behaves as a group of one.
     */
    default PersonClaims claims() {
        return PersonClaims.SOLO;
    }

    /**
     * The maximum method cost currently acceptable, in the walk-block currency methods price
     * themselves in — a costlier applicable method is treated as inapplicable. Set by the arbiter
     * from the active instinct's pressure through {@link ToleranceCurve};
     * {@link Double#POSITIVE_INFINITY} is unbounded — the STARVING plateau, and manual driving.
     */
    double costTolerance();
}
