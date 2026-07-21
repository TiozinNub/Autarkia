package dev.luizloyola.autarkia.core.person;

import java.util.Objects;

/**
 * A person's <em>external</em> identity: the render-relevant data continuously synced to every
 * nearby client. The rest of {@link PersonIdentity} is server-side and sent only when relevant.
 *
 * <p>{@link #skin()} is a texture asset-id. Body/model type and skin <em>variety</em> follow once
 * there are assets to vary — today every person uses the single default skin.
 */
public record Appearance(Gender gender, String skin) {
    public Appearance {
        Objects.requireNonNull(gender, "gender");
        Objects.requireNonNull(skin, "skin");
    }

    public Appearance withGender(Gender newGender) {
        return new Appearance(newGender, skin);
    }

    public Appearance withSkin(String newSkin) {
        return new Appearance(gender, newSkin);
    }
}
