package dev.luizloyola.autarkia.core.person;

import dev.luizloyola.anima.core.agent.AgentId;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * In-memory directory of every known person's {@link PersonIdentity}, keyed by {@link AgentId}: a
 * plain map, no persistence or Minecraft knowledge. The {@code mod} layer wraps it in a persisted
 * {@code PersonDirectory}; a {@code Person} entity holds only an {@link AgentId}, so a loaded person
 * can refer to an unloaded one by id.
 *
 * <p>Insertion order is preserved for stable iteration (deterministic tests and on-disk output).
 */
public final class PersonRegistry {
    private final Map<AgentId, PersonIdentity> byId = new LinkedHashMap<>();

    /** Adds or replaces an identity. Returns the stored identity for chaining. */
    public PersonIdentity register(PersonIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        byId.put(identity.id(), identity);
        return identity;
    }

    /** Registers a new person from an id + name + appearance, rejecting a duplicate id. */
    public PersonIdentity create(AgentId id, String name, Appearance appearance) {
        if (byId.containsKey(id)) {
            throw new IllegalArgumentException("person already registered: " + id);
        }
        return register(new PersonIdentity(id, name, appearance));
    }

    public Optional<PersonIdentity> get(AgentId id) {
        return Optional.ofNullable(byId.get(id));
    }

    public boolean contains(AgentId id) {
        return byId.containsKey(id);
    }

    /** Removes an identity outright — the dev purge path; real deaths KEEP identity. */
    public boolean remove(AgentId id) {
        return byId.remove(id) != null;
    }

    /** All known identities, in insertion order; unmodifiable view. */
    public Collection<PersonIdentity> all() {
        return Collections.unmodifiableCollection(byId.values());
    }

    public int size() {
        return byId.size();
    }
}
