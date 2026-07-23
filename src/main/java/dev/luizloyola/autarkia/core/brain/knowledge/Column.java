package dev.luizloyola.autarkia.core.brain.knowledge;

/**
 * One (x, z) coordinate — a vertical stack of blocks, the unit the crescent sampler emits and
 * the probe classifies. Perception looks at columns from above (heightmap first); the y arrives
 * only once the probe resolves the column's surface.
 */
public record Column(int x, int z) {
}
