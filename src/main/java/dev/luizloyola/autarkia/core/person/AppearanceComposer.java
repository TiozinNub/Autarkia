package dev.luizloyola.autarkia.core.person;

import dev.luizloyola.anima.core.appearance.Part;
import dev.luizloyola.anima.core.appearance.Recipe;
import java.util.List;

/**
 * Turns a person's {@link Appearance} into the {@link Recipe} Anima bakes from — the seam the
 * product boundary rests on: Anima ships the machinery and knows nothing about faces, moods or
 * people, while the vocabulary is Autarkia's. A pets mod would write its own composer.
 *
 * <p>A {@link Look.Skin} is one part covering the whole canvas with no recolouring, so it bakes to
 * a texture byte-identical to the PNG it names.
 */
public final class AppearanceComposer {
    private AppearanceComposer() {}

    /** A player skin sheet. That is what a Person's texture is and will remain. */
    public static final int CANVAS_WIDTH = 64;
    public static final int CANVAS_HEIGHT = 64;

    /**
     * An {@code instanceof} ladder rather than a pattern switch: {@code core} compiles at Java 17.
     * The trailing fallback replaces the exhaustiveness a sealed switch would give — a new
     * {@link Look} that forgets to compose here draws the default, written to be obvious on screen.
     */
    public static Recipe compose(Appearance appearance) {
        Look look = appearance.look();
        if (look instanceof Look.Skin skin) {
            return Recipe.of(CANVAS_WIDTH, CANVAS_HEIGHT,
                    List.of(Part.whole(skin.assetId(), CANVAS_WIDTH, CANVAS_HEIGHT)));
        }
        return Recipe.of(CANVAS_WIDTH, CANVAS_HEIGHT,
                List.of(Part.whole(PersonSkins.DEFAULT_SKIN, CANVAS_WIDTH, CANVAS_HEIGHT)));
    }
}
