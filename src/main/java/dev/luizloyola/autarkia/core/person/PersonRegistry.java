package dev.luizloyola.autarkia.core.person;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * In-memory directory of every known person's {@link PersonIdentity}, keyed by {@link PersonId} —
 * the pure, unit-testable heart of the identity store; the {@code mod} layer wraps it in a
 * world-scoped, persisted {@code PersonDirectory}. The {@code Person} entity holds only a
 * {@link PersonId} and resolves through here, so a loaded person can refer to an unloaded one.
 * Insertion order is preserved for deterministic tests and on-disk output.
 */
public final class PersonRegistry {
    private final Map<PersonId, PersonIdentity> byId = new LinkedHashMap<>();

    /** Adds or replaces an identity. Returns the stored identity for chaining. */
    public PersonIdentity register(PersonIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        byId.put(identity.id(), identity);
        return identity;
    }

    public PersonIdentity create(PersonId id, String name) {
        if (byId.containsKey(id)) {
            throw new IllegalArgumentException("person already registered: " + id);
        }
        return register(new PersonIdentity(id, name));
    }

    public Optional<PersonIdentity> get(PersonId id) {
        return Optional.ofNullable(byId.get(id));
    }

    public boolean contains(PersonId id) {
        return byId.containsKey(id);
    }

    /** All known identities, in insertion order; unmodifiable view. */
    public Collection<PersonIdentity> all() {
        return Collections.unmodifiableCollection(byId.values());
    }

    public int size() {
        return byId.size();
    }
}
