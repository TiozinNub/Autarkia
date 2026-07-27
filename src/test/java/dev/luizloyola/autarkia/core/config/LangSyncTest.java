package dev.luizloyola.autarkia.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Keeps every translation in step with {@code en_us.json}, the source language. A missing key is
 * invisible in play (the game silently falls back to English), and {@code en_us.json} itself is
 * enforced by {@link ConfigLabelsTest}, so it is the other files that rot.
 *
 * <p>Locales are discovered from the lang directory, so a new {@code es_es.json} is covered the
 * moment it lands. Keys are scraped with a regex: no JSON library, no Minecraft on the classpath.
 *
 * <p>Not asserted: that a translation <em>differs</em> from its English source. Plenty of correct
 * translations are identical words ({@code category.social} is "Social" either way).
 */
class LangSyncTest {

    private static final String LANG_DIR = "/assets/autarkia/lang";
    private static final String SOURCE = "en_us.json";

    @Test
    @DisplayName("every translation carries the full set of en_us keys, and invents none")
    void translationsCoverTheSourceLanguage() {
        Map<String, String> source = entries(SOURCE);
        assertFalse(source.isEmpty(), SOURCE + " yielded no keys — did the file move?");

        for (String locale : translations()) {
            Map<String, String> translated = entries(locale);

            TreeSet<String> missing = new TreeSet<>(source.keySet());
            missing.removeAll(translated.keySet());
            assertTrue(missing.isEmpty(),
                    locale + " is missing " + missing.size() + " key(s) present in " + SOURCE
                            + " — translate them, or the game silently falls back to English "
                            + "for those lines: " + missing);

            TreeSet<String> unknown = new TreeSet<>(translated.keySet());
            unknown.removeAll(source.keySet());
            assertTrue(unknown.isEmpty(),
                    locale + " has key(s) that " + SOURCE + " does not — a typo'd key never "
                            + "renders, and a renamed one leaves the old string behind: " + unknown);
        }
    }

    @Test
    @DisplayName("a translation takes the same %s arguments as its English source")
    void placeholdersSurviveTranslation() {
        // The game feeds a fixed argument list to whichever string the active language supplies, so
        // a translation that grew or lost a %s breaks when that line is built — whenever the
        // message happens to fire.
        Map<String, String> source = entries(SOURCE);

        for (String locale : translations()) {
            for (Map.Entry<String, String> line : entries(locale).entrySet()) {
                String english = source.get(line.getKey());
                if (english == null) {
                    continue; // already reported, with a better message, by the sibling test
                }
                assertEquals(placeholders(english), placeholders(line.getValue()),
                        locale + " changes the format arguments of " + line.getKey()
                                + " — English \"" + english + "\" vs \"" + line.getValue() + "\"");
            }
        }
    }

    /** Every lang file except the source — the ones that can fall behind. */
    private static List<String> translations() {
        Path dir = langDir();
        List<String> found = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.json")) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                if (!name.equals(SOURCE)) {
                    found.add(name);
                }
            }
        } catch (IOException e) {
            throw new AssertionError("could not list " + dir, e);
        }
        found.sort(null);
        return found;
    }

    private static Path langDir() {
        URL url = LangSyncTest.class.getResource(LANG_DIR);
        assertNotNull(url, "missing resource directory " + LANG_DIR);
        assertEquals("file", url.getProtocol(),
                "expected " + LANG_DIR + " on the classpath as a directory, not " + url);
        try {
            return Paths.get(url.toURI());
        } catch (URISyntaxException e) {
            throw new AssertionError("could not resolve " + url, e);
        }
    }

    /**
     * Key/value pairs in file order. Values keep their JSON escapes ({@code \"}, {@code \n}) since
     * nothing here inspects the prose — only the keys and the {@code %s} arguments, neither of which
     * an escape can hide inside.
     */
    private static Map<String, String> entries(String file) {
        Map<String, String> found = new LinkedHashMap<>();
        Matcher m = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
                .matcher(read(file));
        while (m.find()) {
            String previous = found.put(m.group(1), m.group(2));
            assertEquals(null, previous, file + " defines " + m.group(1) + " twice");
        }
        return found;
    }

    /** The format arguments a line takes, in order — {@code %s}, {@code %1$s}, {@code %d}, … */
    private static List<String> placeholders(String value) {
        List<String> found = new ArrayList<>();
        Matcher m = Pattern.compile("%(?:\\d+\\$)?[a-zA-Z%]").matcher(value);
        while (m.find()) {
            if (!m.group().equals("%%")) {
                found.add(m.group());
            }
        }
        return found;
    }

    private static String read(String file) {
        String path = LANG_DIR + "/" + file;
        try (InputStream in = LangSyncTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "missing resource " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("could not read " + path, e);
        }
    }
}
