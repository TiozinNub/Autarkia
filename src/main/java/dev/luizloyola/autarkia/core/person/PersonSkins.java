package dev.luizloyola.autarkia.core.person;

import java.util.List;
import java.util.random.RandomGenerator;

/**
 * Gendered pools of skin texture asset-ids for new persons.
 *
 * <p>Imported from the {@code citizenshipmod} prototype: male skins are authored for the wide model,
 * female skins for the slim model (see {@link ModelType}), so a person's model type follows their
 * gender. Ids are in asset-id form ({@code namespace:path}, without {@code textures/} or {@code .png}).
 * The skin string is opaque here — the bundled PNGs and the renderer give it meaning.
 */
public final class PersonSkins {
    private PersonSkins() {}

    static final List<String> MALE_SKINS = List.of(
            "autarkia:entity/person/male/skin_0",
            "autarkia:entity/person/male/skin_1",
            "autarkia:entity/person/male/skin_2",
            "autarkia:entity/person/male/skin_3",
            "autarkia:entity/person/male/skin_4",
            "autarkia:entity/person/male/skin_5");

    static final List<String> FEMALE_SKINS = List.of(
            "autarkia:entity/person/female/skin_0",
            "autarkia:entity/person/female/skin_1",
            "autarkia:entity/person/female/skin_2",
            "autarkia:entity/person/female/skin_3",
            "autarkia:entity/person/female/skin_4");

    public static String random(RandomGenerator random, Gender gender) {
        List<String> pool = gender.choose(MALE_SKINS, FEMALE_SKINS);
        return pool.get(random.nextInt(pool.size()));
    }
}
