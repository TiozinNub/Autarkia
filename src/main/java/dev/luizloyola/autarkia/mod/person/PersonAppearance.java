package dev.luizloyola.autarkia.mod.person;

import dev.luizloyola.anima.core.appearance.catalog.Catalog;
import dev.luizloyola.anima.core.appearance.catalog.CatalogReader;
import dev.luizloyola.anima.core.appearance.catalog.Choices;
import dev.luizloyola.autarkia.mod.AutarkiaMod;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import org.jspecify.annotations.Nullable;

/**
 * Autarkia's appearance vocabulary: the catalog, the art that exists, and the choices those two
 * allow.
 *
 * <p>Read from the mod jar, not a resource manager: a {@code ResourceManager} holds <em>client</em>
 * resources and server {@code data/} never crosses the wire, so a catalog loaded as server data
 * would be present in single-player and absent on a real server — the only place where the client
 * composes alone. The jar is on both sides, so {@link ModContainer} answers the same everywhere,
 * with no sync and no reload listener; the cost is pack override.
 *
 * <p>The server rolls a genotype (ladder lengths, the styles that exist), the client composes and
 * bakes (slots, ramps, anchors), and both must agree about the file.
 */
public final class PersonAppearance {
    private PersonAppearance() {}

    /** Where the catalog lives inside the jar — {@code data/} as the spec placed it, read directly. */
    private static final String CATALOG = "data/autarkia/appearance/catalog.json";

    /** The art tree a Person is drawn from. One folder per choice: body, hair, shirts, pants, … */
    private static final String ART_ROOT = "assets/autarkia/textures/person";

    // Never initialised in a field declaration: a static added by a hot swap arrives null on a class
    // with live instances, so the accessor rebuilds it rather than dying mid-bake.
    private static @Nullable Catalog catalog;
    private static @Nullable Set<String> art;
    private static @Nullable Map<String, List<String>> choices;

    /** The catalog, or {@code null} if the jar does not carry a readable one. */
    public static @Nullable Catalog catalog() {
        if (catalog == null) {
            catalog = readCatalog();
        }
        return catalog;
    }

    /**
     * Every texture id the mod ships under {@code person/}, in catalog id form: the set
     * {@link Choices} reads, and the {@code exists} predicate a compose passes so
     * {@code ["shirt_{model}", "shirt"]} resolves to the specific cut only when somebody drew one.
     */
    public static Set<String> art() {
        if (art == null) {
            art = walkArt();
        }
        return art;
    }

    /** Whether that id is art this mod ships — the {@code exists} predicate, by another name. */
    public static boolean has(String textureId) {
        return art().contains(textureId);
    }

    /**
     * What each of the catalog's parameters may be set to, given the art that exists. Anima's rules,
     * and the same call the appearance editor makes: a settler is rolled from the styles the
     * picker offers.
     */
    public static Map<String, List<String>> choices() {
        if (choices == null) {
            Catalog loaded = catalog();
            choices = loaded == null ? Map.of() : Choices.of(loaded, art());
        }
        return choices;
    }

    /** The values one parameter may take, or an empty list if the catalog does not read it. */
    public static List<String> choicesFor(String parameter) {
        return choices().getOrDefault(parameter, List.of());
    }

    /**
     * What each parameter may be, once some are decided. Not cached, unlike {@link #choices()}:
     * asked once per settler over a couple of dozen texture ids, and a cache keyed by an arbitrary
     * parameter set would cost more than it saves.
     */
    public static Map<String, List<String>> choicesGiven(Map<String, String> known) {
        Catalog loaded = catalog();
        return loaded == null ? Map.of() : Choices.of(loaded, art(), known);
    }

    /**
     * What the wardrobe came to, as lines to log once at startup.
     *
     * <p>Everything below degrades <em>quietly</em> — a catalog that did not load, art that did not
     * ship, a family that came out empty all end in settlers who merely look wrong, indistinguishable
     * from art nobody has drawn yet — so the counts go in the log.
     */
    public static List<String> describe() {
        Catalog loaded = catalog();
        if (loaded == null) {
            return List.of("appearance: NO CATALOG — Persons will fall back to plain skins");
        }
        StringBuilder families = new StringBuilder();
        choices().forEach((parameter, values) -> families.append(families.isEmpty() ? "" : ", ")
                .append(parameter).append('=').append(values.size()));
        return List.of("appearance: catalog has " + loaded.slots().size() + " slot(s), "
                        + loaded.ramps().size() + " ramp(s), " + loaded.ladders().size() + " ladder(s); "
                        + art().size() + " texture(s) shipped",
                "appearance: choices — " + (families.isEmpty() ? "none" : families));
    }

    private static @Nullable Catalog readCatalog() {
        try (InputStream stream = PersonAppearance.class.getClassLoader().getResourceAsStream(CATALOG)) {
            if (stream == null) {
                AutarkiaMod.LOGGER.error("appearance: no catalog at {} in the jar — "
                        + "every Person will fall back to a plain skin", CATALOG);
                return null;
            }
            return CatalogReader.read(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException unreadable) {
            // A catalog is authored: name the file and the fault loudly, and fall back rather than
            // failing a world load over a comma.
            AutarkiaMod.LOGGER.error("appearance: {} could not be read ({}) — "
                    + "every Person will fall back to a plain skin", CATALOG, unreadable.toString());
            return null;
        }
    }

    /**
     * {@link ModContainer#findPath} rather than a classloader scan: through Loader's path a jar is a
     * filesystem to {@code Files.walk} and a dev run a directory, and this works in both.
     */
    private static Set<String> walkArt() {
        ModContainer container = FabricLoader.getInstance().getModContainer(AutarkiaMod.MOD_ID).orElse(null);
        if (container == null) {
            return Set.of();
        }
        Set<String> found = new LinkedHashSet<>();
        for (Path root : container.getRootPaths()) {
            Path person = root.resolve(ART_ROOT);
            if (!Files.isDirectory(person)) {
                continue;
            }
            try (Stream<Path> entries = Files.walk(person)) {
                entries.filter(path -> path.getFileName().toString().endsWith(".png"))
                        .forEach(path -> found.add("autarkia:person/"
                                + person.relativize(path).toString().replace('\\', '/')
                                        .replaceAll("\\.png$", "")));
            } catch (IOException unreadable) {
                AutarkiaMod.LOGGER.warn("appearance: could not walk {}: {}", person, unreadable.getMessage());
            }
        }
        if (found.isEmpty()) {
            AutarkiaMod.LOGGER.warn("appearance: no art found under {} — composed looks will draw nothing",
                    ART_ROOT);
        }
        return Set.copyOf(found);
    }
}
