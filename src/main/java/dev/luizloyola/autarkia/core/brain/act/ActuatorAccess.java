package dev.luizloyola.autarkia.core.brain.act;

/**
 * The bundle of actuator ports a task ticks against — handed to {@code PrimitiveTask.tick}/
 * {@code cancel} by the executor each call rather than held: a task owns intent, never the body,
 * and tasks stay constructible in tests with no wiring.
 *
 * <p>Assembled by the mod {@code BrainDriver} over the compat/mod actuator facades. One PORT per
 * actuator domain, never one method per verb: each domain owns its own begin / state / stop
 * lifecycle and this bundle only hands them out. Same shape as {@code Percepts}.
 */
public interface ActuatorAccess {
    /** The legs — see {@link Mover}. */
    Mover mover();

    /** The gullet — see {@link ItemConsumer}. */
    ItemConsumer consumer();
}
