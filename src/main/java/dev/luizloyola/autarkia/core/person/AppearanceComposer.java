package dev.luizloyola.autarkia.core.person;

import dev.luizloyola.anima.core.appearance.Part;
import dev.luizloyola.anima.core.appearance.Recipe;
import dev.luizloyola.anima.core.appearance.catalog.Catalog;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

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
     * What is true of a person that the catalog reads but a genotype does not carry.
     *
     * <p>Placeholders for now — an expression, a blink and a spoken syllable arrive through this
     * seam. Until then everybody is awake, neutral and quiet.
     */
    public static final Map<String, String> RESTING_STATE =
            Map.of("mood", "neutral", "blink", "false", "speaking", "false");

    /**
     * The recipe for one person as they currently are.
     *
     * <p>An {@code instanceof} ladder, not a pattern switch: {@code core} is Java 17. A new
     * {@link Look} that forgets to compose here draws the default rather than failing to compile.
     *
     * @param catalog what a composed look is composed from, or {@code null} where none loaded — in
     *                which case a genotype degrades to the default skin rather than to nothing. A
     *                settler with no catalog must look wrong, not be invisible.
     * @param exists  whether a texture is shipped, so {@code ["shirt_{model}", "shirt"]} picks the
     *                specific cut only when somebody drew one
     */
    public static Recipe compose(Appearance appearance, @Nullable Catalog catalog,
                                 Predicate<String> exists) {
        return compose(appearance, catalog, exists, RESTING_STATE);
    }

    /**
     * Compose with no catalog — resolves a <em>pinned</em> look exactly and degrades everything else
     * to the default skin.
     *
     * <p>Not for use in the game, where {@code PersonAppearance} always has the catalog; it exists
     * so a pinned skin can be tested without standing up a wardrobe.
     */
    public static Recipe compose(Appearance appearance) {
        return compose(appearance, null, texture -> false);
    }

    /** As above, with what is currently true of them supplied rather than assumed. */
    public static Recipe compose(Appearance appearance, @Nullable Catalog catalog,
                                 Predicate<String> exists, Map<String, String> state) {
        Look look = appearance.look();
        // A pinned texture is composed by naming it and nothing else: whatever the catalog grows,
        // "this person wears this file" keeps meaning that.
        if (look instanceof Look.Skin skin) {
            return whole(skin.assetId());
        }
        if (look instanceof Look.Composed composed && catalog != null) {
            return catalog.compose(params(appearance, composed, state), bindings(catalog, composed), exists);
        }
        return whole(PersonSkins.DEFAULT_SKIN);
    }

    /**
     * Everything the catalog's selectors may read: the body's own geometry, the families this person
     * was rolled into, and what is true of them this instant.
     *
     * <p>State is applied last where they overlap, so a live value always beats a rolled one.
     */
    private static Map<String, String> params(Appearance appearance, Look.Composed composed,
                                              Map<String, String> state) {
        Map<String, String> params = new LinkedHashMap<>();
        // The catalog's one word of body vocabulary. It names the geometry rather than implying it,
        // and it is the same thing the arm model is picked by, so there is one control and not two.
        params.put("model", appearance.model().name().toLowerCase(Locale.ROOT));
        params.putAll(composed.choices());
        params.putAll(state);
        return params;
    }

    /**
     * A ladder position becomes a colour here and nowhere else.
     *
     * <p>Binding names are the ladder's name in upper case, which is the convention the catalog's own
     * {@code defaultBindings} already uses — so a ladder added to the file is bindable from the file
     * too, with nothing to change here.
     */
    private static Map<String, Integer> bindings(Catalog catalog, Look.Composed composed) {
        Map<String, Integer> bindings = new LinkedHashMap<>();
        composed.ladders().forEach((name, index) ->
                bindings.put(name.toUpperCase(Locale.ROOT), catalog.ladder(name, index)));
        return bindings;
    }

    private static Recipe whole(String assetId) {
        return Recipe.of(CANVAS_WIDTH, CANVAS_HEIGHT,
                List.of(Part.whole(assetId, CANVAS_WIDTH, CANVAS_HEIGHT)));
    }
}
