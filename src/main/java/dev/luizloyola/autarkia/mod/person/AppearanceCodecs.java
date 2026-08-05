package dev.luizloyola.autarkia.mod.person;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.luizloyola.autarkia.core.person.Appearance;
import dev.luizloyola.autarkia.core.person.Gender;
import dev.luizloyola.autarkia.core.person.Look;
import dev.luizloyola.autarkia.core.person.ModelType;
import dev.luizloyola.autarkia.core.person.PersonSkins;
import java.util.function.Function;

/**
 * How an {@link Appearance} is written into, and read out of, the {@link PersonDirectory}.
 *
 * <p>Split out of the directory <b>so it can be tested</b>: vanilla parses saved data with
 * {@code resultOrPartial}, so a wrong codec does not fail — it loads with rows quietly missing.
 * The directory names {@code SavedData}, {@code Identifier} and {@code DataFixTypes} and cannot
 * load headless; this names only DataFixerUpper and the core types, so a test reads a schema-1 row
 * through the real codec.
 *
 * <p>In {@code mod}, not {@code core}, to keep DataFixerUpper out of the core layer.
 */
public final class AppearanceCodecs {
    private AppearanceCodecs() {}

    private static final Codec<Gender> GENDER = Codec.STRING.xmap(Gender::valueOf, Gender::name);
    private static final Codec<ModelType> MODEL = Codec.STRING.xmap(ModelType::valueOf, ModelType::name);

    /**
     * Schema 2 and onward: {@link Appearance#encode()}, the same single string the entity syncs to
     * clients. One representation, so disk and wire cannot drift.
     */
    public static final Codec<Appearance> ENCODED =
            Codec.STRING.xmap(Appearance::decode, Appearance::encode);

    /**
     * Schema 1: a {@code {gender, skin, model}} compound, {@code model} optional because it
     * postdated the other two. Read-only in practice — {@link #CODEC} always encodes through
     * {@link #ENCODED} — so the getters, though total, are never called.
     */
    public static final Codec<Appearance> LEGACY = RecordCodecBuilder.create(appearance -> appearance.group(
            GENDER.fieldOf("gender").forGetter(Appearance::gender),
            Codec.STRING.fieldOf("skin").forGetter(worn -> worn.look() instanceof Look.Skin skin
                    ? skin.assetId()
                    : PersonSkins.DEFAULT_SKIN),
            MODEL.optionalFieldOf("model", ModelType.WIDE).forGetter(Appearance::model)
    ).apply(appearance, (gender, skin, model) -> new Appearance(gender, model, new Look.Skin(skin))));

    /**
     * What the directory uses: the current form first, the old one as a fallback, and always
     * written in the current form — so an existing world loads and rewrites itself on its next save
     * without a migration pass.
     */
    public static final Codec<Appearance> CODEC = Codec.either(ENCODED, LEGACY)
            .xmap(either -> either.map(Function.identity(), Function.identity()), Either::left);
}
