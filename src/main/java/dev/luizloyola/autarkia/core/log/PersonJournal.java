package dev.luizloyola.autarkia.core.log;

import dev.luizloyola.autarkia.core.person.PersonId;
import java.util.List;
import java.util.Objects;

/**
 * A person's-eye view onto the shared {@link JournalService}: a stateless flyweight pinning one
 * {@link PersonId}, so callers write {@code journal.record(BRAIN, "wander", "start")} and any
 * number may be handed out for the same person. What {@code BrainContext.journal()} returns for a
 * loaded Person; offline sim binds one per {@code PersonId} it simulates — no entity required.
 */
public record PersonJournal(JournalService service, PersonId id) {
    public PersonJournal {
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(id, "id");
    }

    /** Record one line for this person — see {@link JournalService#record}. */
    public void record(Category category, String event, String detail) {
        service.record(id, category, event, detail);
    }

    /** This person's last {@code max} lines, oldest-first — see {@link JournalService#recent}. */
    public List<Entry> recent(int max) {
        return service.recent(id, max);
    }
}
