package dev.luizloyola.autarkia.core.person;

/**
 * The one texture a Person falls back to when nothing else can be worked out — reached only when
 * the catalog could not be read at all. A settler with no catalog must look <b>wrong</b>, not be
 * invisible and not be magenta, and vanilla's own Steve is the one texture every client has.
 *
 * <p><b>Autarkia bundles no whole-canvas skin PNGs</b> — the wardrobe under
 * {@code textures/person/} is layered garments, drawn here and accounted for in NOTICE. Public
 * skin galleries license nothing to a downloader — an uploader grants the SITE a licence, not the
 * world — while naming a vanilla texture ships nothing.
 *
 * <p>Ids are asset ids ({@code namespace:path}, without {@code textures/} or {@code .png}). The
 * string is opaque to the rest of the simulation; the renderer gives it meaning.
 */
public final class PersonSkins {
    private PersonSkins() {}

    /** Where vanilla keeps them: {@code assets/minecraft/textures/entity/player/<cut>/<name>.png}. */
    private static final String VANILLA = "minecraft:entity/player/";

    /** The one every client certainly has, and therefore the fallback for a person with no look on
     *  record or one nothing recognises. Lives here rather than on the entity so {@code core} can
     *  name it — {@link Look#DEFAULT} and {@link Appearance#DEFAULT} both need it, and neither may
     *  reach into {@code mod}. */
    public static final String DEFAULT_SKIN = VANILLA + "wide/steve";
}
