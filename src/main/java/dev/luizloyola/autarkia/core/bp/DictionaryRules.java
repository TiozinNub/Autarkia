package dev.luizloyola.autarkia.core.bp;

import dev.luizloyola.autarkia.core.bp.Dictionary.BlockInfo;
import dev.luizloyola.autarkia.core.bp.Dictionary.Material;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * Derives materials and forms from block ids. Autarkia's rules rather than vanilla's
 * {@code BlockFamily}, whose variant list grows between versions: the same file has to bind on
 * every node. A modded block joins by the same rules in its own namespace.
 */
public final class DictionaryRules {

    /** Longest first, so {@code light_blue_wool} is never read as {@code blue} + {@code wool}. */
    public static final List<String> COLOURS = List.of("light_blue", "light_gray", "white", "orange",
            "magenta", "yellow", "lime", "pink", "gray", "cyan", "purple", "blue", "brown", "green", "red",
            "black");

    /**
     * How many of the 16 colours must have a form for it to be a colour's form at all — so
     * {@code blue_ice} and {@code red_sand} stay what they are.
     */
    static final int COLOUR_FORM_MIN = 12;

    /** The suffixes that make a stone-like base a family — vanilla has these for stone, not for dirt. */
    private static final List<String> FAMILY_MARKS = List.of("stairs", "slab", "wall");

    private static final List<String> FAMILY_SUFFIXES = List.of("stairs", "slab", "wall", "button",
            "pressure_plate", "fence", "fence_gate", "door", "trapdoor");

    private static final List<String> FAMILY_PREFIXES = List.of("chiseled", "cracked", "polished", "cut",
            "smooth", "mossy");

    /** One form reaches every wood: a crimson stem is a log, bamboo's block is its log. */
    private static final Map<String, String> WOOD_ALIASES = Map.of("stem", "log", "hyphae", "wood",
            "block", "log");

    /** The dictionary, and any block two rules claimed for the same form — a rules bug to report. */
    public record Derived(Dictionary dictionary, List<String> conflicts) {
        public Derived {
            conflicts = List.copyOf(conflicts);
        }
    }

    private DictionaryRules() {
    }

    /**
     * @param netherPlanks the planks in {@code minecraft:non_flammable_wood}, which is what splits
     *                     {@code nether_wood} from {@code overworld_wood}
     */
    public static Derived derive(Collection<BlockInfo> blocks, Set<String> netherPlanks) {
        Map<String, BlockInfo> byId = new TreeMap<>();
        Map<String, Set<String>> byNamespace = new TreeMap<>();
        for (BlockInfo block : blocks) {
            byId.put(block.id(), block);
            byNamespace.computeIfAbsent(Ids.namespace(block.id()), ns -> new TreeSet<>()).add(Ids.path(block.id()));
        }
        Map<String, Map<String, String>> forms = new TreeMap<>();
        List<String> conflicts = new ArrayList<>();
        List<String> woods = new ArrayList<>();
        List<String> colours = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : byNamespace.entrySet()) {
            String ns = entry.getKey();
            Set<String> paths = entry.getValue();
            Rules rules = new Rules(ns, paths, forms, conflicts);
            woods.addAll(rules.woods());
            colours.addAll(rules.colours());
            rules.families(woodPrefixes(paths));
        }
        Map<String, Material> materials = new TreeMap<>();
        forms.forEach((id, table) -> materials.put(id, new Material(id, new TreeMap<>(table), List.of())));
        List<String> overworld = new ArrayList<>();
        List<String> nether = new ArrayList<>();
        for (String wood : woods) {
            String planks = forms.get(wood).get("planks");
            (planks != null && netherPlanks.contains(planks) ? nether : overworld).add(wood);
        }
        composite(materials, "minecraft:wood", woods);
        composite(materials, "minecraft:overworld_wood", overworld);
        composite(materials, "minecraft:nether_wood", nether);
        composite(materials, "minecraft:any_color",
                colours.stream().filter(id -> Ids.namespace(id).equals("minecraft")).toList());
        return new Derived(new Dictionary(byId, materials), conflicts);
    }

    private static void composite(Map<String, Material> materials, String id, List<String> members) {
        if (!members.isEmpty() && !materials.containsKey(id)) {
            materials.put(id, new Material(id, Map.of(), members.stream().sorted().toList()));
        }
    }

    private static List<String> woodPrefixes(Set<String> paths) {
        List<String> prefixes = new ArrayList<>();
        for (String path : paths) {
            if (path.endsWith("_planks")) {
                prefixes.add(path.substring(0, path.length() - "_planks".length()));
            }
        }
        prefixes.sort(Comparator.comparingInt(String::length).reversed().thenComparing(Comparator.naturalOrder()));
        return prefixes;
    }

    /** One namespace's pass; every table it fills is keyed by the qualified material id. */
    private static final class Rules {
        private final String ns;
        private final Set<String> paths;
        private final Map<String, Map<String, String>> forms;
        private final List<String> conflicts;

        Rules(String ns, Set<String> paths, Map<String, Map<String, String>> forms, List<String> conflicts) {
            this.ns = ns;
            this.paths = paths;
            this.forms = forms;
            this.conflicts = conflicts;
        }

        private String id(String path) {
            return ns + ":" + path;
        }

        private void put(String material, String form, String path) {
            Map<String, String> table = forms.computeIfAbsent(id(material), m -> new TreeMap<>());
            String block = id(path);
            String was = table.putIfAbsent(form, block);
            if (was != null && !was.equals(block)) {
                conflicts.add(id(material) + " " + form + ": " + was + " and " + block);
            }
        }

        List<String> woods() {
            List<String> prefixes = woodPrefixes(paths);
            for (String path : paths) {
                boolean stripped = path.startsWith("stripped_");
                String rest = stripped ? path.substring("stripped_".length()) : path;
                for (String prefix : prefixes) {
                    if (!rest.startsWith(prefix + "_")) {
                        continue;
                    }
                    String form = rest.substring(prefix.length() + 1);
                    form = WOOD_ALIASES.getOrDefault(form, form);
                    put(prefix, stripped ? "stripped_" + form : form, path);
                    break;
                }
            }
            for (String prefix : prefixes) {
                put(prefix, "block", prefix + "_planks");
            }
            return prefixes.stream().map(this::id).sorted().toList();
        }

        List<String> colours() {
            Map<String, Map<String, String>> byForm = new HashMap<>();
            for (String path : paths) {
                for (String colour : COLOURS) {
                    if (path.startsWith(colour + "_")) {
                        byForm.computeIfAbsent(path.substring(colour.length() + 1), f -> new TreeMap<>())
                                .put(colour, path);
                        break;
                    }
                }
            }
            Set<String> found = new TreeSet<>();
            byForm.forEach((form, members) -> {
                if (members.size() >= COLOUR_FORM_MIN) {
                    members.forEach((colour, path) -> {
                        put(colour, form, path);
                        found.add(id(colour));
                    });
                }
            });
            return List.copyOf(found);
        }

        void families(List<String> woodPrefixes) {
            Map<String, String> stemOf = new TreeMap<>();
            for (String path : paths) {
                for (String mark : FAMILY_MARKS) {
                    if (!path.endsWith("_" + mark)) {
                        continue;
                    }
                    String stem = path.substring(0, path.length() - mark.length() - 1);
                    if (woodPrefixes.contains(stem)) {
                        break;
                    }
                    String base = base(stem);
                    if (base != null) {
                        stemOf.putIfAbsent(base, stem);
                    }
                    break;
                }
            }
            stemOf.forEach((base, stem) -> {
                put(base, "block", base);
                for (String suffix : FAMILY_SUFFIXES) {
                    if (paths.contains(stem + "_" + suffix)) {
                        put(base, suffix, stem + "_" + suffix);
                    }
                }
                for (String prefix : FAMILY_PREFIXES) {
                    if (paths.contains(prefix + "_" + base)) {
                        put(base, prefix, prefix + "_" + base);
                    }
                }
            });
        }

        /** Vanilla's naming: {@code stone_brick_stairs} is of {@code stone_bricks}, quartz of its block. */
        private @Nullable String base(String stem) {
            for (String candidate : List.of(stem, stem + "s", stem + "_block")) {
                if (paths.contains(candidate)) {
                    return candidate;
                }
            }
            return null;
        }
    }
}
