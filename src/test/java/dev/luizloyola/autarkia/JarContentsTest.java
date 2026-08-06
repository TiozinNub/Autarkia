package dev.luizloyola.autarkia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.luizloyola.anima.arch.ModJar;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Checks the jar this build produced — see Anima's copy for why the jar's edge is worth a test.
 *
 * <p>One rule is Autarkia's alone: it declares {@code "anima": "<exact version>"}, built from the
 * same commit, so that a mismatched pair fails loudly at load rather than subtly at runtime. A pin
 * that is not this build's own is always a mistake.
 */
class JarContentsTest {

    private static final String MOD_ID = "autarkia";
    private static final String LICENCE = "GPL-3.0-only";

    /**
     * The licence text's own heading. Autarkia is the strong-copyleft half of the repo and Anima
     * the weak one; shipping either file under the other's name has consequences outside the code.
     */
    private static final String LICENCE_HEADING = "GNU GENERAL PUBLIC LICENSE";

    /** Where a jar entry is allowed to be — see Anima's copy. */
    private static final List<String> ALLOWED_PREFIXES = List.of(
            "dev/luizloyola/" + MOD_ID + "/", "assets/" + MOD_ID + "/", "data/" + MOD_ID + "/",
            "META-INF/");

    /** Top-level files that belong in the jar by name. */
    private static final List<String> ALLOWED_FILES = List.of(
            "fabric.mod.json", MOD_ID + ".mixins.json", MOD_ID + ".accesswidener", MOD_ID + ".ct",
            "LICENSE", "TRADEMARKS.md");

    private static final ModJar JAR = ModJar.fromSystemProperty("autarkia.jar");
    private static final JsonObject METADATA =
            JsonParser.parseString(JAR.text("fabric.mod.json")).getAsJsonObject();

    @Test
    @DisplayName("the licence and the trademark notice travel with the jar")
    void legalTextIsPackaged() {
        assertTrue(JAR.has("LICENSE"), JAR.name() + " ships no LICENSE");
        assertTrue(JAR.has("TRADEMARKS.md"), JAR.name() + " ships no TRADEMARKS.md — the licences "
                + "deliberately say nothing about the name, so the jar would otherwise imply the "
                + "name came with the code");
        assertTrue(JAR.text("LICENSE").contains(LICENCE_HEADING),
                "LICENSE does not contain \"" + LICENCE_HEADING + "\", but fabric.mod.json declares "
                        + LICENCE + " — one of the two is wrong");
        assertEquals(LICENCE, METADATA.get("license").getAsString(),
                "fabric.mod.json declares a licence this mod does not ship");
    }

    @Test
    @DisplayName("the metadata names this mod, at the version that was just built")
    void metadataMatchesTheBuild() {
        assertEquals(MOD_ID, METADATA.get("id").getAsString());
        assertEquals(System.getProperty("autarkia.version"), METADATA.get("version").getAsString(),
                "the version in the jar is not the version Gradle built — processResources did not "
                        + "expand it, or the two came from different runs");
    }

    @Test
    @DisplayName("the Anima dependency is pinned to this exact build")
    void animaIsPinnedToTheVersionBesideIt() {
        JsonObject depends = METADATA.getAsJsonObject("depends");
        assertTrue(depends.has("anima"), "fabric.mod.json declares no dependency on anima at all — "
                + "the two are a matched pair built from one commit, and Loader is the only thing "
                + "that can refuse a mismatched one");
        assertEquals(METADATA.get("version").getAsString(), depends.get("anima").getAsString(),
                "the pinned anima version is not this build's own — a range or a stale literal "
                        + "turns a loud load-time refusal into a subtle runtime failure");
    }

    @Test
    @DisplayName("the declared access widener is in the jar")
    void accessWidenerIsPackaged() {
        // Named `autarkia.ct` on every node: the FORMAT header swaps between accessWidener and
        // classTweaker across targets (see stonecutter.gradle.kts), the filename does not.
        String declared = METADATA.has("accessWidener")
                ? METADATA.get("accessWidener").getAsString()
                : METADATA.has("classTweaker") ? METADATA.get("classTweaker").getAsString() : null;
        assertTrue(declared != null, "fabric.mod.json declares neither accessWidener nor "
                + "classTweaker, but this mod needs one to widen what its mixins reach");
        assertTrue(JAR.has(declared),
                declared + " is declared by fabric.mod.json but is not in the jar — Loader fails "
                        + "the mod at load when it cannot find the file");
    }

    @Test
    @DisplayName("every mixin the config names is actually in the jar")
    void mixinTargetsArePackaged() {
        String config = MOD_ID + ".mixins.json";
        assertTrue(METADATA.getAsJsonArray("mixins").asList().stream()
                        .anyMatch(e -> e.getAsString().equals(config)),
                "fabric.mod.json does not list " + config + ", so none of the mixins apply at all");
        assertTrue(JAR.has(config), config + " is named by fabric.mod.json but is not in the jar");

        JsonObject mixins = JsonParser.parseString(JAR.text(config)).getAsJsonObject();
        String pkg = mixins.get("package").getAsString().replace('.', '/');
        List<String> missing = new ArrayList<>();
        for (String side : List.of("mixins", "client", "server")) {
            if (!mixins.has(side)) {
                continue;
            }
            for (var entry : mixins.getAsJsonArray(side)) {
                String clazz = pkg + "/" + entry.getAsString().replace('.', '/') + ".class";
                if (!JAR.has(clazz)) {
                    missing.add(side + ": " + entry.getAsString() + " (looked for " + clazz + ")");
                }
            }
        }
        assertTrue(missing.isEmpty(), config + " names classes the jar does not carry — the mod "
                + "dies at load, and it dies for every player at once: " + missing);
    }

    @Test
    @DisplayName("nothing rode along that nobody put there — Anima least of all")
    void theJarCarriesOnlyItsOwnFiles() {
        List<String> strays = JAR.entriesOutside(ALLOWED_PREFIXES).stream()
                .filter(e -> !ALLOWED_FILES.contains(e))
                .filter(e -> !e.matches(".*refmap.*\\.json"))
                .toList();
        assertTrue(strays.isEmpty(), () -> JAR.name() + " carries " + strays.size()
                + " unexpected entr(y/ies): " + strays);

        // Anima is a SEPARATE DOWNLOAD (decision: Luiz) — deliberately not nested, so that a player
        // running two Anima consumers does not carry two copies and let Loader pick between them.
        List<String> nested = JAR.entries().stream()
                .filter(e -> e.startsWith("META-INF/jars/") || e.contains("dev/luizloyola/anima/"))
                .toList();
        assertTrue(nested.isEmpty(), "Anima has been bundled into Autarkia's jar — jar-in-jar is "
                + "deliberately not used here; fabric.mod.json's pin is what ties the pair: " + nested);
    }
}
