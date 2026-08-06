package dev.luizloyola.autarkia.core.person;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * <em>How</em> a person's texture is arrived at — the part of an {@link Appearance} that decides
 * what gets drawn, as opposed to the body it is drawn on.
 *
 * <p>{@link Skin} is a whole authored PNG, which an operator can pin; {@link Composed} is a genotype
 * (a position on each colour ladder and a member of each family) composited into one.
 *
 * <p>Adding a variant is free; changing an appearance's <em>shape</em> moves the store's schema and
 * the entity's synced fields, and the latter cannot be hot-swapped in.
 *
 * <p>{@link #decode} obeys a pinned skin over anything composed: it is the more specific statement.
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
     * A look assembled from the catalog: where this person sits on each colour ladder, and which
     * member of each family they wear.
     *
     * <p>Maps keyed by the catalog's own names, not fields: adding a ladder or a family is then a
     * JSON edit and a PNG rather than a Java change, a schema change and a migration. An entry the
     * catalog no longer has is not read.
     *
     * <p>Nothing that changes lives here: mood, blink, speech and job are state, supplied at compose
     * time. That is what lets this be rolled once.
     *
     * @param ladders position on each named colour ladder — {@code skin}, {@code hair}, {@code eye},
     *                the cloth ladders. An index, not a colour: the ladder is the curated set of
     *                values a body may take, and storing the position rather than the RGB is what
     *                makes a tone heritable and what stops a generated crowd from being a scatter.
     * @param choices which member of each family — {@code hairstyle=long}, {@code shirt=plain}. By
     *                <b>name</b>, never by index: a pack that adds a hairstyle would otherwise
     *                silently renumber everybody else's.
     */
    record Composed(Map<String, Integer> ladders, Map<String, String> choices) implements Look {

        /** Prefixes that mark a field as belonging to this look, and say which half it is. */
        public static final String LADDER_PREFIX = "l.";
        public static final String CHOICE_PREFIX = "p.";

        public Composed {
            ladders = Map.copyOf(Objects.requireNonNull(ladders, "ladders"));
            choices = Map.copyOf(Objects.requireNonNull(choices, "choices"));
        }

        /**
         * <b>Sorted</b>, and load-bearing: this string is compared to decide whether a person's look
         * moved and is hashed into the id of the texture they wear, so two agents in the same
         * clothes must produce the same bytes — and a map's iteration order is no promise.
         */
        @Override
        public String encode() {
            StringBuilder out = new StringBuilder();
            new java.util.TreeMap<>(ladders).forEach((name, index) ->
                    out.append(out.isEmpty() ? "" : ";").append(LADDER_PREFIX).append(name)
                            .append('=').append(index));
            new java.util.TreeMap<>(choices).forEach((name, value) ->
                    out.append(out.isEmpty() ? "" : ";").append(CHOICE_PREFIX).append(name)
                            .append('=').append(value));
            return out.toString();
        }

        @Override
        public String describe() {
            StringBuilder out = new StringBuilder();
            new java.util.TreeMap<>(choices).forEach((name, value) ->
                    out.append(out.isEmpty() ? "" : " ").append(value));
            new java.util.TreeMap<>(ladders).forEach((name, index) ->
                    out.append(' ').append(name).append('#').append(index));
            return out.toString().trim();
        }
    }

    /**
     * Reads whichever look the decoded fields describe.
     *
     * <p>Total: an unrecognised look yields {@link #DEFAULT} rather than an error, so an
     * unreadable row comes back looking wrong rather than vanishing. Silent row loss is a failure
     * this project has paid for once.
     *
     * <p><b>A pinned skin wins over a composed one</b> when a row carries both, being the more
     * specific statement.
     */
    static Look decode(Map<String, String> fields) {
        String skin = fields.get(Skin.KEY);
        if (skin != null && !skin.isEmpty()) {
            return new Skin(skin);
        }
        Map<String, Integer> ladders = new LinkedHashMap<>();
        Map<String, String> choices = new LinkedHashMap<>();
        fields.forEach((key, value) -> {
            if (key.startsWith(Composed.LADDER_PREFIX)) {
                // A ladder position that is not a number is one field lost, not a person: the ladder
                // wraps on any index, so the fallback draws something rather than nothing.
                try {
                    ladders.put(key.substring(Composed.LADDER_PREFIX.length()), Integer.parseInt(value));
                } catch (NumberFormatException notANumber) {
                    ladders.put(key.substring(Composed.LADDER_PREFIX.length()), 0);
                }
            } else if (key.startsWith(Composed.CHOICE_PREFIX) && !value.isEmpty()) {
                choices.put(key.substring(Composed.CHOICE_PREFIX.length()), value);
            }
        });
        return ladders.isEmpty() && choices.isEmpty() ? DEFAULT : new Composed(ladders, choices);
    }
}
