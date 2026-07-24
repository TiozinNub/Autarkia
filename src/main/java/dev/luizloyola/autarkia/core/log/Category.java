package dev.luizloyola.autarkia.core.log;

/**
 * The subsystem a {@link Entry journal entry} came from — the third column of a log line
 * ({@code Bob - <category> - event - detail}). A closed enum, not a free string, so the debug
 * command and the file sink filter and colour without parsing.
 *
 * <ul>
 *   <li>{@link #BRAIN} — the winning instinct, a task tree starting or finishing, autonomy.</li>
 *   <li>{@link #PATHFIND} — a route requested, accepted or failed, a recalculate, a stray.</li>
 *   <li>{@link #BODY} — damage (source + new health), starvation, death, eating.</li>
 *   <li>{@link #SENSE} — a POI noticed into or forgotten from the knowledge store.</li>
 *   <li>{@link #PROJECT} — the board (posted / closed / cooldown) and the arbiter's commitments
 *       (claimed / started / suspended / resumed / completed / failed).</li>
 * </ul>
 *
 * <p>The enum, not the emitter's location, decides the column: an entity-free layer 3/4 logs
 * through the same service against its {@code PersonId}.
 */
public enum Category {
    BRAIN,
    PATHFIND,
    BODY,
    SENSE,
    PROJECT
}
