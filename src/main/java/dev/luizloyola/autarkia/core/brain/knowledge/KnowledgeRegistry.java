package dev.luizloyola.autarkia.core.brain.knowledge;

import dev.luizloyola.autarkia.core.person.PersonId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Every person's {@link PersonKnowledge}, keyed by {@link PersonId}: memories belong to the
 * <em>person</em>, not the entity, so they survive chunk unloads and can be read offline. Pure and
 * world-agnostic; the {@code mod} layer wraps it in a world-scoped SavedData (the
 * {@code PersonDirectory} pattern) and rebuilds it on load by replaying {@code note()}.
 */
public final class KnowledgeRegistry {
    private final Map<PersonId, PersonKnowledge> byPerson = new LinkedHashMap<>();

    /** This person's knowledge, created empty on first ask — never null, always the same object. */
    public PersonKnowledge forPerson(PersonId id) {
        Objects.requireNonNull(id, "id");
        return byPerson.computeIfAbsent(id, k -> new PersonKnowledge());
    }

    /** Every person who has (or ever asked for) knowledge, insertion-ordered — the codec's view. */
    public Set<PersonId> persons() {
        return Collections.unmodifiableSet(byPerson.keySet());
    }

    /** Drops a person's knowledge outright — the dev purge path. */
    public boolean remove(PersonId id) {
        return byPerson.remove(id) != null;
    }
}
