package dev.luizloyola.autarkia.core.person;

import java.util.Objects;
import java.util.UUID;

/**
 * A stable, permanent handle to a person, independent of any in-world entity: it may be referenced
 * while the entity is unloaded, exist before one ever spawns (offline/abstract simulation), or be
 * remembered after death. Its own value, therefore, not a reuse of the entity's Minecraft UUID.
 */
public record PersonId(UUID value) {
    public PersonId {
        Objects.requireNonNull(value, "value");
    }

    public static PersonId of(UUID value) {
        return new PersonId(value);
    }

    public static PersonId random() {
        return new PersonId(UUID.randomUUID());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
