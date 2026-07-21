package dev.luizloyola.autarkia.core.person;

import java.util.Objects;

/**
 * A person's <em>full</em> identity — the server-authoritative record, independent of any in-world
 * entity, bundling both transport tiers: {@link #appearance()} is the <em>external</em>,
 * render-relevant data (gender, skin), continuously synced to nearby clients; everything else
 * ({@link #name()}, later skills/traits/relationships) is server-side, sent only when relevant. The
 * directory is the single source of truth; the entity holds a reference and projects the appearance
 * onto its synced fields.
 */
public record PersonIdentity(PersonId id, String name, Appearance appearance) {
    public PersonIdentity {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(appearance, "appearance");
    }

    public PersonIdentity withName(String newName) {
        return new PersonIdentity(id, newName, appearance);
    }

    public PersonIdentity withAppearance(Appearance newAppearance) {
        return new PersonIdentity(id, name, newAppearance);
    }
}
