package dev.luizloyola.autarkia.core.brain.sense;

/**
 * One dropped item currently in sight — the {@link Threat} shape for loose loot: cell position
 * plus the item's id string ({@link Percepts#drops()}). A bare sighting; ground-vs-stranded is
 * the consumer's question, answered through {@link Percepts#blocks()}.
 */
public record Drop(Pos pos, String itemId) {
}
