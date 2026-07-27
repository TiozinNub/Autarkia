package dev.luizloyola.autarkia.core.brain.sense;

/**
 * A nearby hostile, sensed right now — what {@code FleeInstinct} turns into pressure and
 * {@code FleeStep} into an escape vector. {@code targetingMe} is whether the threat's own AI has
 * THEM as its target: a wandering hostile two blocks away presses less than one locked on from
 * twice the distance.
 *
 * @param pos the threat's position, whole blocks (the pathfinder/Navigator grid, like {@link Pos})
 * @param distance straight-line distance from their position to {@code pos}, in blocks
 * @param targetingMe whether the threat is actively hunting them, not merely nearby
 */
public record Threat(Pos pos, double distance, boolean targetingMe) {
}
