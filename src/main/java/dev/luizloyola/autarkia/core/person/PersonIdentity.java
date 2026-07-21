package dev.luizloyola.autarkia.core.person;

import java.util.Objects;

/**
 * A person's <em>full</em> identity — server-authoritative, living independently of any in-world
 * entity and sent to clients only when relevant (e.g. on inspection), as against the
 * <em>external</em> identity (skin, gender) synced continuously via the entity.
 *
 * <p>Only the {@link #name()} today; it grows without changing the storage or transport model.
 */
public record PersonIdentity(PersonId id, String name) {
    public PersonIdentity {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
    }

    public PersonIdentity withName(String newName) {
        return new PersonIdentity(id, newName);
    }
}
