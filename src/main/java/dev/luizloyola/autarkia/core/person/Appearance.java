package dev.luizloyola.autarkia.core.person;

import java.util.Objects;

/**
 * A person's <em>external</em> identity: the small, render-relevant data continuously synced to
 * nearby clients — {@link #skin()} a texture asset-id, {@link #model()} the geometry it is drawn
 * for (wide/slim). The rest of {@link PersonIdentity} is server-side, sent only when relevant. A
 * new person draws a skin from their gender's pool; the model follows the skin.
 */
public record Appearance(Gender gender, String skin, ModelType model) {
    public Appearance {
        Objects.requireNonNull(gender, "gender");
        Objects.requireNonNull(skin, "skin");
        Objects.requireNonNull(model, "model");
    }

    public Appearance withGender(Gender newGender) {
        return new Appearance(newGender, skin, model);
    }

    public Appearance withSkin(String newSkin) {
        return new Appearance(gender, newSkin, model);
    }

    public Appearance withModel(ModelType newModel) {
        return new Appearance(gender, skin, newModel);
    }
}
