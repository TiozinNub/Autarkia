package dev.luizloyola.autarkia.core.brain.sense;

/**
 * A whole-block cell — the brain's spatial unit, matching the pathfinder/Navigator grid (feet
 * position, integer coordinates). Kept a bare core record with no {@code net.minecraft}
 * dependency; the mod adapter converts a {@code BlockPos} into one at the boundary.
 */
public record Pos(int x, int y, int z) {
}
