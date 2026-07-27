package dev.luizloyola.autarkia.core.brain.sense;

/**
 * One dropped item currently in sight: cell position plus the item's id string (the core
 * inventory's item vocabulary). Supplied by the mod from budgeted entity queries around them
 * ({@link Percepts#drops()}); ground-versus-stranded is the consumer's question, answered through
 * {@link Percepts#blocks()}.
 */
public record Drop(Pos pos, String itemId) {
}
