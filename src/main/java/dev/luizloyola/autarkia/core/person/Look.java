package dev.luizloyola.autarkia.core.person;

import java.util.Map;
import java.util.Objects;

/**
 * <em>How</em> a person's texture is arrived at — the part of an {@link Appearance} that decides
 * what gets drawn, as opposed to the body it is drawn on.
 *
 * <p>One case today: {@link Skin}, a whole authored PNG. Sealed ahead of the second on purpose —
 * adding a variant here is free, while changing the <em>shape</em> of {@link Appearance} moves the
 * store's schema and the entity's synced fields, which cannot be hot-swapped in.
 *
 * <p>A pinned literal skin stays valid forever, however rich composed looks become.
 */
public sealed interface Look {

    /** The default when a person has no look on record, or one nothing here recognises. */
    Look DEFAULT = new Skin(PersonSkins.DEFAULT_SKIN);

    /** This look's {@code key=value} fragment inside an encoded {@link Appearance}. */
    String encode();

    /** A short, human line for a command to print. */
    String describe();

    /** A whole authored texture, named by asset id ({@code namespace:path}, no {@code textures/}
     *  prefix and no {@code .png} — the pipeline adds both). */
    record Skin(String assetId) implements Look {
        /** The field name this look occupies in an encoded appearance. */
        public static final String KEY = "skin";

        public Skin {
            Objects.requireNonNull(assetId, "assetId");
        }

        @Override
        public String encode() {
            return KEY + "=" + assetId;
        }

        @Override
        public String describe() {
            return assetId;
        }
    }

    /**
     * Reads whichever look the decoded fields describe.
     *
     * <p>Total: an unrecognised look yields {@link #DEFAULT}, never an error. A person
     * whose row cannot be read must come back visibly wrong rather than vanish.
     */
    static Look decode(Map<String, String> fields) {
        String skin = fields.get(Skin.KEY);
        return skin == null || skin.isEmpty() ? DEFAULT : new Skin(skin);
    }
}
