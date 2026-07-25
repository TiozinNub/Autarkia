package dev.luizloyola.autarkia.mod.config;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import dev.luizloyola.autarkia.core.config.AutarkiaConfig;
import dev.luizloyola.autarkia.core.config.Config;
import dev.luizloyola.autarkia.core.config.Knob;
import dev.luizloyola.autarkia.mod.AutarkiaMod;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;

/**
 * The JSON face of {@link AutarkiaConfig} — {@code config/autarkia.json}, read at startup and on
 * {@code /autarkia config reload}, written back whenever a knob changes. The shape is derived from
 * {@link Knob}: one object per {@link Knob#section()}, one entry per {@link Knob#leaf()}.
 *
 * <p><b>Self-documenting.</b> Each value is preceded by a {@code "// name"} string holding the
 * knob's own doc sentence — JSON has no comment syntax, but a key starting with {@code //} is
 * skipped on read, which keeps the parser dependency-free. Rewriting refreshes those lines from the
 * code.
 *
 * <p><b>Nothing here throws at the caller.</b> A missing file is written from the defaults; a
 * malformed one is reported and the defaults used, leaving the operator's file untouched. Only
 * {@link #save} surfaces I/O failure, and only as a log line.
 */
public final class ConfigFile {

    private static final String FILE_NAME = "autarkia.json";
    /** Prefix marking a generated documentation line rather than a value. */
    private static final String DOC_PREFIX = "// ";

    private ConfigFile() {
    }

    /** {@code <game dir>/config/autarkia.json}. */
    public static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }

    /**
     * Reads the file and {@linkplain Config#install installs} what it found — the one call both
     * startup and {@code /autarkia config reload} make. Returns the human-readable notes worth
     * showing (clamped values, unparseable entries, unknown keys); empty means a clean load.
     * Creates the file from the defaults when it does not exist yet.
     */
    public static List<String> reload() {
        Path path = path();
        if (!Files.exists(path)) {
            Config.install(AutarkiaConfig.DEFAULTS);
            save(AutarkiaConfig.DEFAULTS);
            AutarkiaMod.LOGGER.info("Autarkia config: wrote defaults to {}", path);
            return List.of();
        }

        JsonObject root;
        try {
            String text = Files.readString(path, StandardCharsets.UTF_8);
            JsonElement parsed = JsonParser.parseString(text);
            if (!parsed.isJsonObject()) {
                return failed(path, "top level is not a JSON object");
            }
            root = parsed.getAsJsonObject();
        } catch (IOException e) {
            return failed(path, "could not be read (" + e.getMessage() + ")");
        } catch (RuntimeException e) {
            // Gson throws JsonSyntaxException/JsonParseException — both unchecked.
            return failed(path, "is not valid JSON (" + e.getMessage() + ")");
        }

        List<String> problems = new ArrayList<>();
        Map<Knob, Double> supplied = new EnumMap<>(Knob.class);
        for (Knob knob : Knob.values()) {
            JsonElement value = lookup(root, knob);
            if (value == null) {
                continue; // absent: the default stands, silently — that is what a fresh file means
            }
            Double read = asNumber(value, knob);
            if (read == null) {
                problems.add(knob.key() + ": expected " + knob.expects()
                        + ", found " + value + " — using " + knob.format(knob.def()));
                continue;
            }
            supplied.put(knob, read);
        }
        problems.addAll(unknownKeys(root));

        AutarkiaConfig.Loaded loaded = AutarkiaConfig.from(supplied);
        problems.addAll(loaded.problems());
        Config.install(loaded.config());

        for (String problem : problems) {
            AutarkiaMod.LOGGER.warn("Autarkia config: {}", problem);
        }
        return List.copyOf(problems);
    }

    /**
     * Writes {@code config} out, replacing the file. Atomic where the filesystem allows it, so a
     * crash mid-write cannot leave a truncated config behind. Returns false (and logs) on failure.
     */
    public static boolean save(AutarkiaConfig config) {
        Path path = path();
        try {
            Files.createDirectories(path.getParent());
            Path tmp = path.resolveSibling(FILE_NAME + ".tmp");
            Files.writeString(tmp, render(config), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException e) {
            AutarkiaMod.LOGGER.error("Autarkia config: could not write {}", path, e);
            return false;
        }
    }

    /** The exact text {@link #save} writes — pulled out so a test can check it round-trips. */
    public static String render(AutarkiaConfig config) {
        JsonObject root = new JsonObject();
        for (Knob knob : Knob.values()) {
            JsonObject section = root.getAsJsonObject(knob.section());
            if (section == null) {
                section = new JsonObject();
                root.add(knob.section(), section);
            }
            section.addProperty(DOC_PREFIX + knob.leaf(), knob.doc());
            section.add(knob.leaf(), toJson(knob, config.get(knob)));
        }
        return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root)
                + System.lineSeparator();
    }

    // --- internals -------------------------------------------------------------------------

    private static List<String> failed(Path path, String why) {
        String message = FILE_NAME + " " + why + " — falling back to defaults; "
                + "the file was left untouched so you can fix it and run "
                + "/autarkia config reload";
        AutarkiaMod.LOGGER.error("Autarkia config: {} ({})", message, path);
        Config.install(AutarkiaConfig.DEFAULTS);
        return List.of(message);
    }

    private static JsonElement lookup(JsonObject root, Knob knob) {
        JsonElement section = root.get(knob.section());
        if (section == null || !section.isJsonObject()) {
            return null;
        }
        return section.getAsJsonObject().get(knob.leaf());
    }

    /** The stored double for a JSON value, or null when it isn't the knob's kind. */
    private static Double asNumber(JsonElement value, Knob knob) {
        if (!value.isJsonPrimitive()) {
            return null;
        }
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        if (knob.kind() == Knob.Kind.BOOL) {
            return primitive.isBoolean() ? (primitive.getAsBoolean() ? 1.0 : 0.0) : null;
        }
        if (!primitive.isNumber()) {
            return null;
        }
        double raw = primitive.getAsDouble();
        if (!Double.isFinite(raw)) {
            return null;
        }
        // An int knob given 12.5 is a mistake worth naming, not silently rounding.
        return knob.kind() == Knob.Kind.INT && raw != Math.rint(raw) ? null : raw;
    }

    private static JsonElement toJson(Knob knob, double value) {
        switch (knob.kind()) {
            case BOOL:
                return new JsonPrimitive(value != 0.0);
            case INT:
                return new JsonPrimitive((long) value);
            case DOUBLE:
            default:
                return new JsonPrimitive(value);
        }
    }

    /**
     * Names entries the mod does not recognise — almost always a typo or a knob an update removed,
     * and either way the operator thinks they set something they did not.
     */
    private static List<String> unknownKeys(JsonObject root) {
        Set<String> known = new LinkedHashSet<>();
        Set<String> sections = new LinkedHashSet<>();
        for (Knob knob : Knob.values()) {
            known.add(knob.key());
            sections.add(knob.section());
        }
        List<String> problems = new ArrayList<>();
        for (String sectionName : root.keySet()) {
            JsonElement section = root.get(sectionName);
            if (!sections.contains(sectionName)) {
                problems.add(String.format(Locale.ROOT, "unknown section \"%s\" — ignored", sectionName));
                continue;
            }
            if (!section.isJsonObject()) {
                problems.add(String.format(Locale.ROOT,
                        "\"%s\" should be an object — ignored", sectionName));
                continue;
            }
            for (String leaf : section.getAsJsonObject().keySet()) {
                if (leaf.startsWith(DOC_PREFIX.trim())) {
                    continue; 
                }
                if (!known.contains(sectionName + "." + leaf)) {
                    problems.add(String.format(Locale.ROOT,
                            "unknown key \"%s.%s\" — ignored", sectionName, leaf));
                }
            }
        }
        return problems;
    }
}
