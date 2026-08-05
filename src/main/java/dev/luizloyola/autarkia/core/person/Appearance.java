package dev.luizloyola.autarkia.core.person;

import dev.luizloyola.anima.core.agent.PublicIdentity;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A person's <em>external</em> identity — the render-relevant data synced to every nearby client.
 * The rest of {@link PersonIdentity} is server-side, sent only when relevant.
 *
 * <h2>One encoding, three consumers</h2>
 * {@link #encode()} and {@link #decode(String)} are the <b>single</b> representation, shared by disk,
 * wire and the round-trip tests, so disk and wire cannot drift — and unlike a codec it can be
 * unit-tested: this project has already lost rows to a codec nobody could test.
 *
 * <p>{@code key=value} pairs joined by {@code ;}; no value may contain either separator, so nothing
 * escapes. Unknown keys are ignored, so a field can be added without a migration.
 *
 * <p><b>{@link #decode} never throws:</b> an exception on the entity-data path takes out the render,
 * and vanilla's {@code resultOrPartial} drops a rejected row silently. An unreadable row must come
 * back visibly wrong, never quietly missing.
 */
public record Appearance(Gender gender, ModelType model, Look look) implements PublicIdentity {

    /** What a person looks like with nothing on record: vanilla's own Steve. */
    public static final Appearance DEFAULT = new Appearance(Gender.MALE, ModelType.WIDE, Look.DEFAULT);

    private static final String FIELD_SEPARATOR = ";";
    private static final char PAIR_SEPARATOR = '=';
    private static final String KEY_GENDER = "gender";
    private static final String KEY_MODEL = "model";

    public Appearance {
        Objects.requireNonNull(gender, "gender");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(look, "look");
    }

    public Appearance withGender(Gender newGender) {
        return new Appearance(newGender, model, look);
    }

    public Appearance withModel(ModelType newModel) {
        return new Appearance(gender, newModel, look);
    }

    public Appearance withLook(Look newLook) {
        return new Appearance(gender, model, newLook);
    }

    /** The wire and on-disk form — see the class note on why there is only one. */
    public String encode() {
        return KEY_GENDER + PAIR_SEPARATOR + gender.name()
                + FIELD_SEPARATOR + KEY_MODEL + PAIR_SEPARATOR + model.name()
                + FIELD_SEPARATOR + look.encode();
    }

    /**
     * Total by design: anything unreadable falls back to its default field by field, so one bad
     * value costs one field and not a person.
     */
    public static Appearance decode(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return DEFAULT;
        }
        Map<String, String> fields = new LinkedHashMap<>();
        for (String pair : encoded.split(FIELD_SEPARATOR, -1)) {
            int split = pair.indexOf(PAIR_SEPARATOR);
            if (split > 0) {
                fields.put(pair.substring(0, split), pair.substring(split + 1));
            }
        }
        return new Appearance(
                readEnum(fields.get(KEY_GENDER), Gender.class, DEFAULT.gender()),
                readEnum(fields.get(KEY_MODEL), ModelType.class, DEFAULT.model()),
                Look.decode(fields));
    }

    /** An enum constant by name, or the given default if the name is absent or no longer exists —
     *  the latter being what happens to a world written by a version that had a value this one
     *  dropped. */
    private static <E extends Enum<E>> E readEnum(String name, Class<E> type, E fallback) {
        if (name == null || name.isEmpty()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException unknown) {
            return fallback;
        }
    }
}
