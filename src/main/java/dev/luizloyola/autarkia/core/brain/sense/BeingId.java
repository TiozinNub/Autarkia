package dev.luizloyola.autarkia.core.brain.sense;

import dev.luizloyola.autarkia.core.person.PersonId;
import java.util.Objects;
import java.util.UUID;

/**
 * A stable handle to one perceived body, not a {@link PersonId}: a cow has spatial
 * continuity but no personhood. A PERSON's id is minted from their person identity, so
 * {@link #asPerson()} converts 1:1; a creature's from the entity UUID; a HERD's fresh when the herd
 * forms, living as long as the cluster keeps a member.
 */
public record BeingId(UUID value) {
    public BeingId {
        Objects.requireNonNull(value, "value");
    }

    public static BeingId of(UUID value) {
        return new BeingId(value);
    }

    /** The person this id names — only meaningful for a {@link Being.Kind#PERSON} being. */
    public PersonId asPerson() {
        return PersonId.of(value);
    }

    public static BeingId of(PersonId person) {
        return new BeingId(person.value());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
