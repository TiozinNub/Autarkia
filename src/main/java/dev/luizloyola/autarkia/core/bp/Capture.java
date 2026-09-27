package dev.luizloyola.autarkia.core.bp;

import dev.luizloyola.autarkia.core.bp.Blueprint.EntryInfo;
import dev.luizloyola.autarkia.core.bp.Blueprint.Facing;
import dev.luizloyola.autarkia.core.bp.Blueprint.Outcome;
import dev.luizloyola.autarkia.core.bp.Blueprint.SlotInfo;
import dev.luizloyola.autarkia.core.bp.Blueprint.SlotKind;
import dev.luizloyola.autarkia.core.bp.BpSource.Grid;
import dev.luizloyola.autarkia.core.bp.BpSource.GroupDecl;
import dev.luizloyola.autarkia.core.bp.BuildPlan.CellKind;
import dev.luizloyola.autarkia.core.bp.Dictionary.BlockInfo;
import dev.luizloyola.autarkia.core.bp.Dictionary.Material;
import dev.luizloyola.autarkia.core.bp.Variants.Selection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * The other way round: blocks read from the world to {@code .bp} text (capture spec, 2026-09-27).
 * Literal — what stands is what is written — but read through the dictionary, so woods and colours
 * come out as pinned slots, and through the planner's rules, so whatever it would decide otherwise
 * is written out.
 */
public final class Capture {

    /** The ids that read as air. */
    private static final Set<String> AIR = Set.of("minecraft:air", "minecraft:cave_air", "minecraft:void_air");

    /** Glyphs to fall back on, in order, once a block's own letters are taken. */
    private static final String POOL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789#*+-=%&^:;!|";

    private Capture() {
    }

    /** A box as read: {@code cells[layer - minLayer][z][x]}, null for air, layer 0 the ground. */
    public record Box(int width, int depth, int minLayer, @Nullable Outcome[][][] cells) {

        public int maxLayer() {
            return minLayer + cells.length - 1;
        }

        public @Nullable Outcome at(int layer, int x, int z) {
            if (layer < minLayer || layer > maxLayer() || x < 0 || x >= width || z < 0 || z >= depth) {
                return null;
            }
            return cells[layer - minLayer][z][x];
        }
    }

    /**
     * The headers a written file carries. A fresh capture cannot know which ways a building may face
     * or whether it may be mirrored, so it says {@code all} and {@code false} and asks the author.
     */
    public record Headers(String name, String author, int version, String orientation, boolean flippable,
                          boolean fresh) {

        public static Headers fresh(String name, String author) {
            return new Headers(name, author, 1, "all", false, true);
        }
    }

    /**
     * The words after a capture's name, in any order: {@code replace}, {@code as group.variant},
     * {@code ground <y>}.
     */
    public record Args(boolean replace, @Nullable String group, @Nullable String variant, @Nullable Integer ground) {

        public static Args parse(String words, Diagnostics out) {
            boolean replace = false;
            String group = null;
            String variant = null;
            Integer ground = null;
            String[] tokens = words.strip().isEmpty() ? new String[0] : words.strip().split("\\s+");
            for (int i = 0; i < tokens.length; i++) {
                String word = tokens[i];
                String next = i + 1 < tokens.length ? tokens[i + 1] : null;
                if (word.equals("replace")) {
                    replace = true;
                } else if (word.equals("as") && next != null && next.matches("[a-z][a-z0-9_]*\\.[a-z0-9_]+")
                        && !next.endsWith(".none")) {
                    group = next.substring(0, next.indexOf('.'));
                    variant = next.substring(next.indexOf('.') + 1);
                    i++;
                } else if (word.equals("ground") && next != null && next.matches("-?[0-9]+")) {
                    ground = Integer.parseInt(next);
                    i++;
                } else {
                    out.error("capture_word", 0, 0, "'" + word + "' is not replace, as <group>.<variant>, or ground "
                            + "<y>");
                }
            }
            if (replace && group != null) {
                out.error("capture_word", 0, 0, "a variant is replaced by capturing it again; replace is for the base");
            }
            return new Args(replace, group, variant, ground);
        }
    }

    /** The text written, or why none was. */
    public record Written(@Nullable String text, List<String> problems) {
        public Written {
            problems = List.copyOf(problems);
        }

        static Written refused(String problem) {
            return new Written(null, List.of(problem));
        }
    }

    // ── one block ───────────────────────────────────────────────────────────────────────────

    /**
     * A block state as a file should say it, null for air: defaults and what the world recomputes
     * dropped, but a bed's foot still says so, and a lantern's {@code hanging} and a button's
     * {@code face} kept whatever their value, since the planner would otherwise decide them again.
     */
    public static @Nullable Outcome normalise(String block, Map<String, String> props, Dictionary dict) {
        if (AIR.contains(block)) {
            return null;
        }
        BlockInfo info = dict.block(block).orElse(null);
        Binder.Fixture fixture = info == null ? null : Binder.fixtureOf(info);
        Map<String, String> kept = new TreeMap<>();
        props.forEach((key, value) -> {
            if (Binder.computed(block, key, value)) {
                return;
            }
            if (fixture == Binder.Fixture.BED && key.equals(fixture.key)) {
                // A bed left unsaid is its head (format spec), whatever vanilla's default.
                if (value.equals(fixture.other)) {
                    kept.put(key, value);
                }
                return;
            }
            boolean settles = key.equals("hanging") || key.equals("face");
            if (settles || info == null || !value.equals(info.defaults().get(key))) {
                kept.put(key, value);
            }
        });
        return new Outcome(block, kept);
    }

    /**
     * {@code attach=floor} on every standing block the planner would otherwise hang on a wall beside
     * it — a torch standing by a wall is written so it stays standing (decision: Luiz, 2026-09-27).
     */
    static Box settle(Box box, Dictionary dict, Support support) {
        @Nullable Outcome[][][] cells = new Outcome[box.cells().length][box.depth()][box.width()];
        for (int layer = box.minLayer(); layer <= box.maxLayer(); layer++) {
            for (int z = 0; z < box.depth(); z++) {
                for (int x = 0; x < box.width(); x++) {
                    Outcome cell = box.at(layer, x, z);
                    if (cell != null && dict.wallTwins().containsKey(cell.block())
                            && !cell.props().containsKey(Planner.ATTACH) && wallBeside(box, support, layer, x, z)) {
                        Map<String, String> props = new TreeMap<>(cell.props());
                        props.put(Planner.ATTACH, "floor");
                        cell = new Outcome(cell.block(), props);
                    }
                    cells[layer - box.minLayer()][z][x] = cell;
                }
            }
        }
        return new Box(box.width(), box.depth(), box.minLayer(), cells);
    }

    /** What the planner asks first: a wall on some side, north, east, south or west. */
    private static boolean wallBeside(Box box, Support support, int layer, int x, int z) {
        for (Facing side : Facing.values()) {
            Outcome wall = box.at(layer, x + side.dx, z + side.dz);
            if (wall != null && support.holds(wall, Support.Face.values()[side.opposite().ordinal()], false)) {
                return true;
            }
        }
        return false;
    }

    // ── a whole file ────────────────────────────────────────────────────────────────────────

    /** A new file from a box: headers, pinned slots, the legend, and every layer, identical ones folded. */
    public static Written write(Box captured, Dictionary dict, Support support, Headers headers) {
        Box box = settle(captured, dict, support);
        Vocabulary words = new Vocabulary(dict, Map.of(), Set.of(), 0);
        Map<Outcome, Character> glyphs = new LinkedHashMap<>();
        for (int layer = box.minLayer(); layer <= box.maxLayer(); layer++) {
            for (int z = 0; z < box.depth(); z++) {
                for (int x = 0; x < box.width(); x++) {
                    Outcome cell = box.at(layer, x, z);
                    if (cell != null && !glyphs.containsKey(cell)) {
                        if (glyphs.size() == Binder.LEGEND_CAP) {
                            return Written.refused("the box holds more than " + Binder.LEGEND_CAP + " different "
                                    + "blocks, and a legend holds at most " + Binder.LEGEND_CAP);
                        }
                        glyphs.put(cell, words.glyph(cell));
                    }
                }
            }
        }
        // Terms first: writing one is what numbers a new slot, and the slots go above the legend.
        Map<Outcome, String> terms = new LinkedHashMap<>();
        glyphs.keySet().forEach(cell -> terms.put(cell, words.term(cell)));
        StringBuilder out = new StringBuilder("bp 1\n\n");
        header(out, "name", headers.name(), null);
        header(out, "author", headers.author(), null);
        header(out, "version", String.valueOf(headers.version()), null);
        header(out, "orientation", headers.orientation(), headers.fresh() ? "captured: which ways may it face?" : null);
        header(out, "flippable", String.valueOf(headers.flippable()),
                headers.fresh() ? "captured: may it be mirrored west to east?" : null);
        if (!words.newSlots.isEmpty()) {
            out.append("\nmaterials\n");
            words.newSlots.forEach((slot, material) -> out.append("  ").append(slot).append(" option ")
                    .append(Ids.brief(material)).append('\n'));
        }
        out.append("\nlegend\n");
        glyphs.forEach((cell, glyph) -> out.append("  ").append(glyph).append(' ').append(terms.get(cell))
                .append('\n'));
        for (Map.Entry<String, List<Integer>> grid : fold(box, glyphs, null).entrySet()) {
            out.append("\nlayer ").append(BpText.indexSet(grid.getValue())).append('\n').append(grid.getKey());
        }
        return new Written(out.toString(), List.of());
    }

    /**
     * A variant captured into an existing file (decision: Luiz, 2026-09-27): each cell compared with
     * what the base would place there, and only what differs written. The file is edited, not
     * rewritten, so its comments and layout survive; capturing a variant again replaces its grids.
     */
    public static Written variant(String text, Blueprint file, Box captured, String group, String variant,
                                  Dictionary dict, Support support) {
        if (captured.width() != file.width() || captured.depth() != file.depth()) {
            return Written.refused("the box is " + captured.width() + "×" + captured.depth() + " and " + file.id()
                    + " is " + file.width() + "×" + file.depth() + "; a variant is captured over the same box");
        }
        Box box = settle(captured, dict, support);
        BuildPlan base = basePlan(file, box, dict, support);
        // An entry of one block first; then one that follows an option slot, so the variant says
        // "the file's wood" where the world shows one — never a mix's, which would roll what stood.
        Map<Outcome, Character> known = new HashMap<>();
        for (boolean single : new boolean[] {true, false}) {
            for (EntryInfo entry : file.legend().values()) {
                if (single ? entry.outcomes().size() == 1 : entry.outcomes().size() > 1 && followsOption(entry, file)) {
                    entry.outcomes().forEach(outcome -> known.putIfAbsent(
                            normalise(outcome.block(), outcome.props(), dict), entry.glyph()));
                }
            }
        }
        Map<String, Integer> pinned = new HashMap<>();
        int highest = 0;
        for (SlotInfo slot : file.slots()) {
            highest = Math.max(highest, slot.number());
            if (slot.kind() == SlotKind.MATERIAL && slot.domain().size() == 1) {
                pinned.putIfAbsent(slot.domain().iterator().next(), slot.number());
            }
        }
        Vocabulary words = new Vocabulary(dict, pinned, file.legend().keySet(), highest);
        Map<Outcome, Character> added = new LinkedHashMap<>();
        char[][][] diff = new char[box.cells().length][box.depth()][box.width()];
        boolean changed = false;
        for (int layer = box.minLayer(); layer <= box.maxLayer(); layer++) {
            for (int z = 0; z < box.depth(); z++) {
                for (int x = 0; x < box.width(); x++) {
                    Outcome world = box.at(layer, x, z);
                    char glyph = compare(file, base, dict, layer, x, z, world);
                    if (glyph == 0) {
                        Character have = known.get(world);
                        if (have == null) {
                            have = added.get(world);
                        }
                        if (have == null) {
                            if (known.size() + added.size() >= Binder.LEGEND_CAP) {
                                return Written.refused("the variant needs more blocks than a legend of "
                                        + Binder.LEGEND_CAP + " holds");
                            }
                            have = words.glyph(world);
                            added.put(world, have);
                        }
                        glyph = have;
                    }
                    changed |= glyph != Blueprint.ANY;
                    diff[layer - box.minLayer()][z][x] = glyph;
                }
            }
        }
        if (!changed) {
            return Written.refused("nothing in the box differs from " + file.id() + "'s base");
        }
        List<String> legendLines = new ArrayList<>();
        added.forEach((cell, glyph) -> legendLines.add("  " + glyph + " " + words.term(cell)));
        String key = group + "." + variant;
        String edited = withoutGrids(text, key);
        edited = withVariant(edited, group, variant);
        if (!words.newSlots.isEmpty()) {
            List<String> lines = new ArrayList<>();
            words.newSlots.forEach((slot, material) -> lines.add("  " + slot + " option " + Ids.brief(material)));
            edited = appendToSection(edited, "materials", lines, "legend");
        }
        if (!legendLines.isEmpty()) {
            edited = appendToSection(edited, "legend", legendLines, null);
        }
        StringBuilder out = new StringBuilder(edited.stripTrailing()).append('\n');
        for (Map.Entry<String, List<Integer>> grid : fold(box, null, diff).entrySet()) {
            out.append("\nlayer ").append(BpText.indexSet(grid.getValue())).append(' ').append(key).append('\n')
                    .append(grid.getKey());
        }
        return new Written(out.toString(), List.of());
    }

    /**
     * What a cell of the variant says, as a glyph: {@code ?} where the world holds what the base
     * would place, {@code .} where the base places a block the world no longer has, and 0 for "the
     * world's own block", which the caller turns into a glyph.
     */
    private static char compare(Blueprint file, @Nullable BuildPlan base, Dictionary dict, int layer, int x, int z,
                                @Nullable Outcome world) {
        char drawn = file.contains(layer, x, z) ? file.glyph(layer, x, z) : Blueprint.ANY;
        CellKind kind = base != null && file.contains(layer, x, z) ? base.kind(layer, x, z) : null;
        Outcome planned = base != null && kind == CellKind.BLOCK ? base.state(layer, x, z) : null;
        boolean placesBlock = planned != null || file.legend().containsKey(drawn);
        if (world == null) {
            return placesBlock ? Blueprint.AIR : Blueprint.ANY;
        }
        if (drawn == Blueprint.TERRAIN) {
            return Blueprint.ANY;
        }
        if (planned != null && world.equals(normalise(planned.block(), planned.props(), dict))) {
            return Blueprint.ANY;
        }
        EntryInfo entry = file.legend().get(drawn);
        if (entry != null) {
            for (Outcome outcome : entry.outcomes()) {
                if (world.equals(normalise(outcome.block(), outcome.props(), dict))) {
                    return Blueprint.ANY;
                }
            }
        }
        return 0;
    }

    private static boolean followsOption(EntryInfo entry, Blueprint file) {
        if (entry.binding() != null || entry.terms().size() != 1
                || !(entry.terms().get(0) instanceof BpSource.SlotRef ref)) {
            return false;
        }
        return file.slots().stream().anyMatch(slot -> slot.number() == ref.slot() && slot.binding() == Binding.OPTION);
    }

    /**
     * The base alone, planned in the materials the world shows, so a door's inferred upper half and
     * a hung torch count as drawn — in spruce when the house stands in spruce.
     */
    private static @Nullable BuildPlan basePlan(Blueprint file, Box box, Dictionary dict, Support support) {
        Blueprint base = file.variants().isEmpty() ? file : file.compose(Selection.BASE);
        return Planner.plan(base, dict, support, pinsFromWorld(base, box, dict), (what, choices) -> 0, new Random(0),
                new Diagnostics());
    }

    /**
     * Each option slot pinned to what most of its cells hold in the world: a cell drawn {@code $1
     * planks} holding spruce planks is a vote for spruce. A mix is never pinned; it is read by
     * membership instead.
     */
    private static Map<Integer, String> pinsFromWorld(Blueprint base, Box box, Dictionary dict) {
        Map<Integer, SlotInfo> slots = new HashMap<>();
        base.slots().forEach(slot -> slots.put(slot.number(), slot));
        Map<Integer, Map<String, Integer>> votes = new HashMap<>();
        for (int layer = base.minLayer(); layer <= base.maxLayer(); layer++) {
            for (int z = 0; z < base.depth(); z++) {
                for (int x = 0; x < base.width(); x++) {
                    EntryInfo entry = base.entryAt(layer, x, z);
                    Outcome world = box.at(layer, x, z);
                    if (entry == null || world == null || entry.terms().size() != 1
                            || !(entry.terms().get(0) instanceof BpSource.SlotRef ref)) {
                        continue;
                    }
                    SlotInfo slot = slots.get(ref.slot());
                    if (slot == null || slot.binding() != Binding.OPTION) {
                        continue;
                    }
                    for (String member : slot.domain()) {
                        boolean holds = slot.kind() == SlotKind.MATERIAL
                                ? ref.form() != null && dict.lookup(member, ref.form()).orElse("").equals(world.block())
                                : member.equals(world.block());
                        if (holds) {
                            votes.computeIfAbsent(slot.number(), n -> new HashMap<>()).merge(member, 1, Integer::sum);
                        }
                    }
                }
            }
        }
        Map<Integer, String> pins = new HashMap<>();
        votes.forEach((slot, tally) -> tally.entrySet().stream().max(Map.Entry.<String, Integer>comparingByValue()
                .thenComparing(Map.Entry.comparingByKey())).ifPresent(best -> pins.put(slot, best.getKey())));
        return pins;
    }

    /** Rows per layer, identical layers folded, each grid as indented rows ready to append. */
    private static Map<String, List<Integer>> fold(Box box, @Nullable Map<Outcome, Character> glyphs,
                                                   char @Nullable [][][] diff) {
        Map<String, List<Integer>> grids = new LinkedHashMap<>();
        for (int layer = box.minLayer(); layer <= box.maxLayer(); layer++) {
            StringBuilder rows = new StringBuilder();
            boolean drawsSomething = diff == null;
            for (int z = 0; z < box.depth(); z++) {
                rows.append("  ");
                for (int x = 0; x < box.width(); x++) {
                    char glyph;
                    if (diff != null) {
                        glyph = diff[layer - box.minLayer()][z][x];
                        drawsSomething |= glyph != Blueprint.ANY;
                    } else {
                        Outcome cell = box.at(layer, x, z);
                        glyph = cell == null ? Blueprint.AIR : glyphs.get(cell);
                    }
                    rows.append(glyph);
                }
                rows.append('\n');
            }
            if (drawsSomething) {
                grids.computeIfAbsent(rows.toString(), k -> new ArrayList<>()).add(layer);
            }
        }
        return grids;
    }

    private static void header(StringBuilder out, String key, String value, @Nullable String comment) {
        String line = key + " ".repeat(Math.max(1, 12 - key.length())) + value;
        out.append(comment == null ? line : line + " ".repeat(Math.max(2, 25 - line.length())) + "// " + comment)
                .append('\n');
    }

    // ── editing a file's text ───────────────────────────────────────────────────────────────

    /** The file without the grids a variant already has, so capturing it again replaces them. */
    private static String withoutGrids(String text, String key) {
        BpSource source = BpParser.parse(text, new Diagnostics());
        List<String> lines = new ArrayList<>(Arrays.asList(text.split("\n", -1)));
        List<Grid> grids = source.grids().stream().filter(grid -> key.equals(grid.variant())).toList();
        for (int g = grids.size() - 1; g >= 0; g--) {
            Grid grid = grids.get(g);
            int from = grid.line() - 1;
            int to = grid.rows().isEmpty() ? from : grid.rows().get(grid.rows().size() - 1).line() - 1;
            if (to + 1 < lines.size() && lines.get(to + 1).isBlank()) {
                to++;
            }
            lines.subList(from, to + 1).clear();
        }
        return String.join("\n", lines);
    }

    /** The variant named in its group's line — a new group declared {@code optional}, for the author to change. */
    private static String withVariant(String text, String group, String variant) {
        BpSource source = BpParser.parse(text, new Diagnostics());
        List<String> lines = new ArrayList<>(Arrays.asList(text.split("\n", -1)));
        for (GroupDecl decl : source.groups()) {
            if (!decl.name().equals(group)) {
                continue;
            }
            if (!decl.variants().contains(variant)) {
                String line = lines.get(decl.line() - 1);
                int comment = line.indexOf("//");
                String code = (comment >= 0 ? line.substring(0, comment) : line).stripTrailing();
                String note = comment >= 0 ? "   " + line.substring(comment) : "";
                lines.set(decl.line() - 1, code + " " + variant + note);
            }
            return String.join("\n", lines);
        }
        return appendToSection(text, "groups", List.of("  " + group + " optional  " + variant), "legend");
    }

    /**
     * Lines added at the end of a section, or the section made — before {@code before}'s header, or
     * at the end — when the file has none.
     */
    private static String appendToSection(String text, String section, List<String> added, @Nullable String before) {
        List<String> lines = new ArrayList<>(Arrays.asList(text.split("\n", -1)));
        int header = columnZero(lines, section);
        if (header >= 0) {
            int last = header;
            for (int i = header + 1; i < lines.size(); i++) {
                String line = lines.get(i);
                String code = line.contains("//") ? line.substring(0, line.indexOf("//")) : line;
                if (code.isBlank()) {
                    if (line.isBlank()) {
                        break;
                    }
                    continue;
                }
                if (!Character.isWhitespace(line.charAt(0))) {
                    break;
                }
                last = i;
            }
            lines.addAll(last + 1, added);
            return String.join("\n", lines);
        }
        List<String> made = new ArrayList<>();
        made.add(section);
        made.addAll(added);
        made.add("");
        int at = before == null ? -1 : columnZero(lines, before);
        if (at < 0) {
            lines.add("");
            lines.addAll(made);
        } else {
            lines.addAll(at, made);
        }
        return String.join("\n", lines);
    }

    private static int columnZero(List<String> lines, String word) {
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String code = line.contains("//") ? line.substring(0, line.indexOf("//")) : line;
            if (code.strip().equals(word) && !line.isEmpty() && !Character.isWhitespace(line.charAt(0))) {
                return i;
            }
        }
        return -1;
    }

    // ── words ───────────────────────────────────────────────────────────────────────────────

    /**
     * How a captured block is written: its slot and form when it is a wood's or a colour's, and a
     * glyph that reads as what it is. Slots already pinned in a file are reused.
     */
    private static final class Vocabulary {
        final Dictionary dict;
        /** Block to {material, form}, for the woods and colours. */
        final Map<String, String[]> reverse = new HashMap<>();
        /** A pinned material to its slot: the file's, then the ones made here. */
        final Map<String, Integer> slots;
        final Map<Integer, String> newSlots = new LinkedHashMap<>();
        final Set<Character> taken;
        int highest;

        Vocabulary(Dictionary dict, Map<String, Integer> pinned, Set<Character> taken, int highest) {
            this.dict = dict;
            this.slots = new HashMap<>(pinned);
            this.taken = new HashSet<>(taken);
            this.highest = highest;
            Set<String> materials = new HashSet<>(dict.leaves("minecraft:wood"));
            materials.addAll(dict.leaves("minecraft:any_color"));
            for (String id : materials) {
                Material material = dict.material(id).orElse(null);
                if (material == null) {
                    continue;
                }
                material.forms().forEach((form, block) -> {
                    String[] was = reverse.get(block);
                    // A wood's planks are both its 'planks' and its 'block'; the plainer word wins.
                    if (was == null || was[1].equals("block")) {
                        reverse.put(block, new String[] {id, form});
                    }
                });
            }
        }

        String term(Outcome cell) {
            String[] slot = reverse.get(cell.block());
            if (slot == null) {
                return Ids.brief(cell.block()) + BpText.props(cell.props());
            }
            Integer number = slots.get(slot[0]);
            if (number == null) {
                number = ++highest;
                slots.put(slot[0], number);
                newSlots.put(number, slot[0]);
            }
            return "$" + number + " " + slot[1] + BpText.props(cell.props());
        }

        /** The glyph a reader would guess, else the block's letters, else the first free one. */
        char glyph(Outcome cell) {
            String path = Ids.path(cell.block());
            String[] slot = reverse.get(cell.block());
            String form = slot == null ? path : slot[1];
            List<Character> wanted = new ArrayList<>();
            if (form.equals("planks") || path.endsWith("_planks")) {
                wanted.add('#');
            } else if (form.endsWith("log") || form.endsWith("wood") || path.endsWith("_stem")
                    || path.endsWith("_hyphae")) {
                String axis = cell.props().getOrDefault("axis", "y");
                wanted.add(axis.equals("x") ? '-' : axis.equals("z") ? '|' : 'o');
            } else if (form.equals("door") || path.endsWith("_door")) {
                wanted.add("upper".equals(cell.props().get("half")) ? 'd' : 'D');
            } else if (form.equals("bed") || path.endsWith("_bed")) {
                wanted.add("foot".equals(cell.props().get("part")) ? 'b' : 'B');
            } else if (path.contains("glass")) {
                wanted.add('G');
            } else if (path.endsWith("torch")) {
                wanted.add('*');
            } else if (path.endsWith("chest")) {
                wanted.add('C');
            } else if (path.equals("crafting_table")) {
                wanted.add('W');
            }
            for (char c : form.replace("_", "").toCharArray()) {
                wanted.add(Character.toUpperCase(c));
                wanted.add(Character.toLowerCase(c));
            }
            for (char c : POOL.toCharArray()) {
                wanted.add(c);
            }
            for (char c : wanted) {
                if (c > 32 && c < 127 && BpParser.RESERVED.indexOf(c) < 0 && taken.add(c)) {
                    return c;
                }
            }
            throw new IllegalStateException("no glyph left");
        }
    }
}
