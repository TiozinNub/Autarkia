package dev.luizloyola.autarkia.core.bp;

import dev.luizloyola.autarkia.core.bp.Dictionary.BlockInfo;
import dev.luizloyola.autarkia.core.bp.Dictionary.Material;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A dictionary as text, so a blueprint can be checked with no game running: the game writes the
 * live one, the offline checker reads it back. Line-oriented and diffable — two versions' files
 * compared side by side show what a Minecraft update did to the vocabulary.
 *
 * <pre>
 * bpdict 1
 * block minecraft:oak_door solid=false light=0 falls=false item=minecraft:oak_door
 *   prop facing north* south west east
 * material minecraft:oak block=minecraft:oak_planks log=minecraft:oak_log
 * composite minecraft:overworld_wood minecraft:acacia minecraft:birch
 * </pre>
 *
 * A {@code *} marks a property's default value.
 */
public final class DictionaryFile {

    private DictionaryFile() {
    }

    public static String write(Dictionary dict, String header) {
        StringBuilder out = new StringBuilder("bpdict 1\n");
        for (String line : header.split("\n")) {
            out.append("// ").append(line).append('\n');
        }
        for (BlockInfo block : dict.blocks().values()) {
            out.append("block ").append(block.id()).append(" solid=").append(block.solid()).append(" light=")
                    .append(block.light()).append(" falls=").append(block.falls()).append(" item=")
                    .append(block.item()).append('\n');
            block.properties().forEach((key, values) -> {
                out.append("  prop ").append(key);
                for (String value : values) {
                    out.append(' ').append(value).append(value.equals(block.defaults().get(key)) ? "*" : "");
                }
                out.append('\n');
            });
        }
        for (Material material : dict.materials().values()) {
            if (material.isComposite()) {
                out.append("composite ").append(material.id());
                material.members().forEach(member -> out.append(' ').append(member));
            } else {
                out.append("material ").append(material.id());
                material.forms().forEach((form, block) -> out.append(' ').append(form).append('=').append(block));
            }
            out.append('\n');
        }
        return out.toString();
    }

    /** @throws IllegalArgumentException naming the line, on anything this writer would not produce */
    public static Dictionary read(String text) {
        List<String> lines = DiagnosticText.lines(text);
        if (lines.isEmpty() || !lines.get(0).trim().equals("bpdict 1")) {
            throw new IllegalArgumentException("line 1: not a dictionary file; it starts with 'bpdict 1'");
        }
        Map<String, BlockInfo> blocks = new LinkedHashMap<>();
        Map<String, Material> materials = new LinkedHashMap<>();
        PendingBlock pending = null;
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("//")) {
                continue;
            }
            String[] words = trimmed.split("\\s+");
            try {
                switch (words[0]) {
                    case "block" -> {
                        if (pending != null) {
                            blocks.put(pending.id, pending.build());
                        }
                        pending = new PendingBlock(words);
                    }
                    case "prop" -> {
                        if (pending == null) {
                            throw new IllegalArgumentException("a prop line belongs under a block");
                        }
                        pending.prop(words);
                    }
                    case "material" -> {
                        Map<String, String> forms = new TreeMap<>();
                        for (String pair : Arrays.asList(words).subList(2, words.length)) {
                            int eq = pair.indexOf('=');
                            forms.put(pair.substring(0, eq), pair.substring(eq + 1));
                        }
                        materials.put(words[1], new Material(words[1], forms, List.of()));
                    }
                    case "composite" -> materials.put(words[1], new Material(words[1], Map.of(),
                            Arrays.asList(words).subList(2, words.length)));
                    default -> throw new IllegalArgumentException("unknown line '" + words[0] + "'");
                }
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("line " + (i + 1) + ": " + e.getMessage(), e);
            }
        }
        if (pending != null) {
            blocks.put(pending.id, pending.build());
        }
        return new Dictionary(blocks, materials);
    }

    private static final class PendingBlock {
        final String id;
        final Map<String, String> fields = new HashMap<>();
        final Map<String, List<String>> properties = new LinkedHashMap<>();
        final Map<String, String> defaults = new HashMap<>();

        PendingBlock(String[] words) {
            id = words[1];
            for (String pair : Arrays.asList(words).subList(2, words.length)) {
                int eq = pair.indexOf('=');
                fields.put(pair.substring(0, eq), pair.substring(eq + 1));
            }
        }

        void prop(String[] words) {
            List<String> values = new ArrayList<>();
            for (String value : Arrays.asList(words).subList(2, words.length)) {
                boolean isDefault = value.endsWith("*");
                String bare = isDefault ? value.substring(0, value.length() - 1) : value;
                values.add(bare);
                if (isDefault) {
                    defaults.put(words[1], bare);
                }
            }
            properties.put(words[1], values);
        }

        BlockInfo build() {
            return new BlockInfo(id, new TreeMap<>(properties), defaults,
                    Boolean.parseBoolean(fields.get("solid")), Integer.parseInt(fields.getOrDefault("light", "0")),
                    Boolean.parseBoolean(fields.get("falls")), fields.getOrDefault("item", ""));
        }
    }
}
