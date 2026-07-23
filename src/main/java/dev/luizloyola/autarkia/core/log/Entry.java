package dev.luizloyola.autarkia.core.log;

import java.util.Objects;

/**
 * One line of a person's log, minus the person ({@code <tick> <category> event - detail}) — an
 * entry is always filed under its owner's {@code PersonId}, so the name is resolved at render time
 * rather than copied onto every line.
 *
 * @param tick     game-time, stamped by {@link JournalService} from its injected clock. Absolute,
 *                 not a per-person age, so two persons' logs line up and deltas read straight off.
 * @param category which subsystem spoke (see {@link Category}).
 * @param event    the short what/where ({@code "wander (10,10,10)"}, {@code "stray"}). Free-form:
 *                 the body knows a {@code DamageSource} the core never will.
 * @param detail   the outcome/how ({@code "success 10 nodes"}). Free-form and optional; a
 *                 {@code null} is normalised to {@code ""}.
 */
public record Entry(long tick, Category category, String event, String detail) {
    public Entry {
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(event, "event");
        detail = detail == null ? "" : detail;
    }
}
