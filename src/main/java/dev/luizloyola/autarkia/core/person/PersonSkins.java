package dev.luizloyola.autarkia.core.person;

import java.util.List;
import java.util.random.RandomGenerator;

/**
 * Gendered pools of skin texture asset-ids for new persons, drawn from the <b>nine default player
 * skins the game already ships</b>.
 *
 * <p>Autarkia bundles no skin PNGs: the ones it used to carry came from public skin galleries,
 * which license nothing to a downloader, so shipping them would have been redistributing other
 * people's work. Vanilla's own textures need no licence and give more variety than the twelve
 * that were bundled.
 *
 * <p>Each of the nine exists in a wide and a slim cut. {@link ModelType} follows {@link Gender}
 * (male wide, female slim), so the model is baked into the id here. Mojang assigned these
 * characters no gender; the two pools are a presentation convention of Autarkia's, one line to
 * change.
 *
 * <p>Ids are asset-ids ({@code namespace:path}, without {@code textures/} or {@code .png}). The
 * skin string is opaque to the simulation; the renderer gives it meaning.
 */
public final class PersonSkins {
    private PersonSkins() {}

    /** Where vanilla keeps them: {@code assets/minecraft/textures/entity/player/<cut>/<name>.png}. */
    private static final String VANILLA = "minecraft:entity/player/";

    static final List<String> MALE_SKINS = List.of(
            VANILLA + "wide/steve",
            VANILLA + "wide/efe",
            VANILLA + "wide/kai",
            VANILLA + "wide/ari");

    static final List<String> FEMALE_SKINS = List.of(
            VANILLA + "slim/alex",
            VANILLA + "slim/makena",
            VANILLA + "slim/noor",
            VANILLA + "slim/sunny",
            VANILLA + "slim/zuri");

    public static String random(RandomGenerator random, Gender gender) {
        List<String> pool = gender.choose(MALE_SKINS, FEMALE_SKINS);
        return pool.get(random.nextInt(pool.size()));
    }

    /**
     * A skin id that certainly resolves: the stored one if this version still ships it, else the
     * gender's first vanilla default.
     *
     * <p>Persons made before the bundled PNGs were deleted point at a texture no longer in the jar,
     * and a missing texture is a magenta-and-black person forever, not a crash. Remapping on read
     * beats a migration pass.
     */
    public static String resolve(String stored, Gender gender) {
        List<String> pool = gender.choose(MALE_SKINS, FEMALE_SKINS);
        return pool.contains(stored) ? stored : pool.get(0);
    }
}
