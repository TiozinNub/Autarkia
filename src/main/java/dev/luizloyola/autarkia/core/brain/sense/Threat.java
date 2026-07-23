package dev.luizloyola.autarkia.core.brain.sense;

/**
 * A nearby hostile — the raw material {@code FleeInstinct} turns into pressure and
 * {@code FleeStep} into an escape vector. Distance is the currency {@code FleeInstinct}'s
 * range/ramp are tuned in; a wandering hostile two blocks away presses less than one that has
 * locked onto her from twice the distance.
 *
 * @param pos the threat's position, whole blocks (the pathfinder/Navigator grid, like {@link Pos})
 * @param distance straight-line distance from her position to {@code pos}, in blocks
 * @param targetingMe whether the threat is actively hunting her, not merely nearby
 */
public record Threat(Pos pos, double distance, boolean targetingMe) {
}
