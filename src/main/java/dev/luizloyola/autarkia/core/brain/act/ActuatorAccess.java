package dev.luizloyola.autarkia.core.brain.act;

/**
 * The bundle of actuator ports a task ticks against — handed to {@code PrimitiveTask.tick}/
 * {@code cancel} by the executor each call rather than held by the task: a task owns intent, never
 * the body. Tasks stay trivially constructible in tests, and whoever ticks one decides which body
 * it drives.
 *
 * <p>Assembled by the mod {@code BrainDriver} over the compat/mod actuator facades; one accessor
 * per actuator domain as the ladder climbs — {@code mover()} today, break/place/use hands later.
 */
public interface ActuatorAccess {
    /** The legs — see {@link Mover}. */
    Mover mover();
}
