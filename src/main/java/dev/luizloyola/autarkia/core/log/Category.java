package dev.luizloyola.autarkia.core.log;

/**
 * The subsystem a {@link Entry journal entry} came from — the third column of a log line
 * ({@code Bob - <category> - event - detail}). A closed enum, not a free string, so the debug
 * command and the file sink filter and colour without parsing.
 *
 * <ul>
 *   <li>{@link #BRAIN} — arbiter decisions, task trees, autonomy flipping.</li>
 *   <li>{@link #PATHFIND} — routes requested, accepted ({@code success N nodes}), failed,
 *       recalculated, strayed off.</li>
 *   <li>{@link #BODY} — damage taken (source + new health), starvation, death, eating.</li>
 *   <li>{@link #SENSE} — a POI noticed into or forgotten from the knowledge store.</li>
 * </ul>
 *
 * <p>Later layers add their own: the enum, not the emitter, decides the column, so an entry-free
 * layer 3/4 logs through the same service by {@code PersonId}.
 */
public enum Category {
    BRAIN,
    PATHFIND,
    BODY,
    SENSE
}
