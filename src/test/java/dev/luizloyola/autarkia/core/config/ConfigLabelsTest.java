package dev.luizloyola.autarkia.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Keeps the GUI's display labels in step with the {@link Knob} registry.
 *
 * <p>The screen reads labels through {@code translatableWithFallback}, so drift degrades instead of
 * breaking: a knob labelled unlike its neighbours, or a dead string left by a rename.
 *
 * <p>Keys are scraped with a regex, so this stays headless (no JSON library, no Minecraft), and it
 * inspects key names only, never values.
 */
class ConfigLabelsTest {

    private static final String LANG = "/assets/autarkia/lang/en_us.json";
    private static final String OPTION_PREFIX = "autarkia.config.option.";
    private static final String CATEGORY_PREFIX = "autarkia.config.category.";

    @Test
    @DisplayName("every knob has a label, and every label belongs to a knob")
    void labelsAndKnobsAgree() {
        Set<String> keys = langKeys();

        Set<String> expected = new TreeSet<>();
        Set<String> expectedCategories = new TreeSet<>();
        for (Knob knob : Knob.values()) {
            expected.add(OPTION_PREFIX + knob.key());
            expectedCategories.add(CATEGORY_PREFIX + knob.section());
        }

        Set<String> actual = new TreeSet<>();
        Set<String> actualCategories = new TreeSet<>();
        for (String key : keys) {
            if (key.startsWith(OPTION_PREFIX) && !key.endsWith(".desc")) {
                actual.add(key);
            } else if (key.startsWith(CATEGORY_PREFIX)) {
                actualCategories.add(key);
            }
        }

        assertEquals(expected, actual,
                "en_us.json option labels have drifted from Knob — add the missing keys, "
                        + "or delete the ones whose knob is gone");
        assertEquals(expectedCategories, actualCategories,
                "en_us.json category labels have drifted from Knob's sections");
    }

    @Test
    @DisplayName("an optional .desc override, if present, must name a real knob")
    void descriptionOverridesNameRealKnobs() {
        // Descriptions fall back to Knob.doc(), so these are optional by design — but a .desc for
        // a knob that no longer exists is a string nobody will ever see again.
        Set<String> known = new TreeSet<>();
        for (Knob knob : Knob.values()) {
            known.add(OPTION_PREFIX + knob.key() + ".desc");
        }
        for (String key : langKeys()) {
            if (key.startsWith(OPTION_PREFIX) && key.endsWith(".desc")) {
                assertTrue(known.contains(key), "no knob matches the description override " + key);
            }
        }
    }

    @Test
    @DisplayName("labels are not just the raw key echoed back")
    void labelsAreActuallyWritten() {
        // A label identical to its leaf means someone added the key to silence this test's sibling
        // without writing a label; the fallback would have produced the same thing for free.
        String json = langSource();
        for (Knob knob : Knob.values()) {
            Matcher m = Pattern.compile(
                    Pattern.quote("\"" + OPTION_PREFIX + knob.key() + "\"") + "\\s*:\\s*\"([^\"]*)\"")
                    .matcher(json);
            assertTrue(m.find(), "no label found for " + knob.key());
            String label = m.group(1);
            assertFalse(label.isBlank(), knob.key() + " has a blank label");
            assertFalse(label.equals(knob.key()) || label.equals(knob.leaf()),
                    knob.key() + " label is just the key — write one or drop the entry and let "
                            + "the fallback handle it");
        }
    }

    private static Set<String> langKeys() {
        Set<String> keys = new LinkedHashSet<>();
        Matcher m = Pattern.compile("\"([^\"]+)\"\\s*:").matcher(langSource());
        while (m.find()) {
            keys.add(m.group(1));
        }
        assertFalse(keys.isEmpty(), LANG + " yielded no keys — did the file move?");
        return keys;
    }

    private static String langSource() {
        try (InputStream in = ConfigLabelsTest.class.getResourceAsStream(LANG)) {
            assertNotNull(in, "missing resource " + LANG);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("could not read " + LANG, e);
        }
    }
}
