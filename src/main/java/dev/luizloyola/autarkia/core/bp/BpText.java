package dev.luizloyola.autarkia.core.bp;

import dev.luizloyola.autarkia.core.bp.Blueprint.EntryInfo;
import dev.luizloyola.autarkia.core.bp.Blueprint.Facing;
import dev.luizloyola.autarkia.core.bp.Blueprint.Node;
import dev.luizloyola.autarkia.core.bp.Blueprint.SlotInfo;
import dev.luizloyola.autarkia.core.bp.Blueprint.SlotKind;
import dev.luizloyola.autarkia.core.bp.BpSource.Named;
import dev.luizloyola.autarkia.core.bp.BpSource.SlotRef;
import dev.luizloyola.autarkia.core.bp.BpSource.Term;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * A bound blueprint back to {@code .bp} text, canonically: headers aligned, identical layers
 * folded into one index set, air as {@code .}. What {@code bp show} prints, and the proof that the
 * reader round-trips — parse what this writes and the same blueprint comes back.
 */
public final class BpText {

    private BpText() {
    }

    public static String term(Term term) {
        String head;
        if (term instanceof Named named) {
            head = named.name();
        } else {
            SlotRef ref = (SlotRef) term;
            head = "$" + ref.slot() + (ref.form() == null ? "" : " " + ref.form());
        }
        return head + props(term.props());
    }

    public static String props(Map<String, String> props) {
        if (props.isEmpty()) {
            return "";
        }
        return new TreeMap<>(props).entrySet().stream().map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(",", "[", "]"));
    }

    public static String union(@Nullable Binding binding, List<Term> terms) {
        String body = terms.stream().map(BpText::term).collect(Collectors.joining("|"));
        return binding == null ? body : binding.word() + " " + body;
    }

    public static String render(Blueprint bp) {
        StringBuilder out = new StringBuilder("bp 1\n\n");
        header(out, "name", bp.headers().name());
        header(out, "author", bp.headers().author());
        header(out, "version", String.valueOf(bp.headers().version()));
        header(out, "orientation", orientation(bp));
        header(out, "flippable", String.valueOf(bp.headers().flippable()));
        header(out, "size", bp.width() + " " + bp.depth() + " " + bp.layers());
        section(out, "materials", bp.slots().stream().filter(s -> s.kind() == SlotKind.MATERIAL).toList());
        section(out, "palette", bp.slots().stream().filter(s -> s.kind() == SlotKind.PALETTE).toList());
        groups(out, bp.variants());
        if (!bp.legend().isEmpty()) {
            out.append("\nlegend\n");
            for (EntryInfo entry : bp.legend().values()) {
                out.append("  ").append(entry.glyph()).append(' ').append(entry.text()).append('\n');
            }
        }
        for (List<Integer> group : layerGroups(bp)) {
            out.append("\nlayer ").append(indexSet(group)).append('\n');
            rows(bp, group.get(0)).forEach(row -> out.append("  ").append(row).append('\n'));
        }
        for (Variants.Overlay overlay : bp.variants().overlays().values()) {
            Map<String, List<Integer>> folded = new LinkedHashMap<>();
            for (int layer : overlay.layers()) {
                folded.computeIfAbsent(String.join("\n", variantRows(bp, overlay.key(), layer)),
                        k -> new ArrayList<>()).add(layer);
            }
            folded.forEach((rows, layers) -> out.append("\nlayer ").append(indexSet(layers)).append(' ')
                    .append(overlay.key()).append('\n').append(rows.lines().map(row -> "  " + row + "\n")
                            .collect(Collectors.joining())));
        }
        for (int layer = bp.minLayer(); layer <= bp.maxLayer(); layer++) {
            if (hasOverlay(bp, layer)) {
                out.append("\nnode layer ").append(layer).append('\n');
                overlayRows(bp, layer).forEach(row -> out.append("  ").append(row).append('\n'));
            }
        }
        if (!bp.nodes().isEmpty()) {
            out.append("\nnodes\n");
            int width = bp.nodes().keySet().stream().mapToInt(String::length).max().orElse(0);
            for (Node node : bp.nodes().values()) {
                out.append("  ").append(pad(node.id(), width)).append(' ').append(node.type()).append('\n');
            }
        }
        if (!bp.edges().isEmpty()) {
            out.append("\npath\n");
            bp.edges().forEach(edge -> out.append("  ").append(edge.from()).append(" > ").append(edge.to())
                    .append('\n'));
        }
        return out.toString();
    }

    /** One layer's grid, north row first, each row with its inline node ids. */
    public static List<String> rows(Blueprint bp, int layer) {
        List<String> rows = new ArrayList<>();
        for (int z = 0; z < bp.depth(); z++) {
            StringBuilder row = new StringBuilder();
            for (int x = 0; x < bp.width(); x++) {
                row.append(bp.glyph(layer, x, z));
            }
            rows.add(row + ids(bp, layer, z, true));
        }
        return rows;
    }

    /**
     * A variant's grid for one layer, {@code ?} where it leaves the base alone; empty when the
     * variant draws nothing there.
     */
    public static List<String> variantRows(Blueprint bp, String key, int layer) {
        Variants.Overlay overlay = bp.variants().overlays().get(key);
        if (overlay == null || !overlay.layers().contains(layer)) {
            return List.of();
        }
        List<String> rows = new ArrayList<>();
        for (int z = 0; z < bp.depth(); z++) {
            StringBuilder row = new StringBuilder();
            for (int x = 0; x < bp.width(); x++) {
                char glyph = overlay.cells()[layer - bp.minLayer()][z][x];
                row.append(glyph == Variants.UNCHANGED ? Blueprint.ANY : glyph);
            }
            rows.add(row.toString());
        }
        return rows;
    }

    /** The node grid drawn over a layer, or nothing when no node sits on a block there. */
    public static List<String> overlayRows(Blueprint bp, int layer) {
        if (!hasOverlay(bp, layer)) {
            return List.of();
        }
        List<String> rows = new ArrayList<>();
        for (int z = 0; z < bp.depth(); z++) {
            char[] row = new char[bp.width()];
            Arrays.fill(row, Blueprint.AIR);
            for (Node node : bp.nodes().values()) {
                if (!node.inline() && node.layer() == layer && node.z() == z) {
                    row[node.x()] = Blueprint.NODE_AIR;
                }
            }
            rows.add(new String(row) + ids(bp, layer, z, false));
        }
        return rows;
    }

    private static void header(StringBuilder out, String key, String value) {
        out.append(pad(key, 11)).append(' ').append(value).append('\n');
    }

    private static String orientation(Blueprint bp) {
        if (bp.headers().orientation().containsAll(EnumSet.allOf(Facing.class))) {
            return "all";
        }
        return Arrays.stream(Facing.values()).filter(bp.headers().orientation()::contains).map(Facing::word)
                .collect(Collectors.joining(" "));
    }

    private static void groups(StringBuilder out, Variants variants) {
        if (variants.isEmpty()) {
            return;
        }
        out.append("\ngroups\n");
        int width = variants.groups().stream().mapToInt(group -> group.name().length()).max().orElse(0);
        for (Variants.Group group : variants.groups()) {
            out.append("  ").append(pad(group.name(), width)).append(' ')
                    .append(group.required() ? "required" : "optional").append(' ')
                    .append(String.join(" ", group.variants())).append('\n');
        }
        variants.needs().forEach((key, targets) -> out.append("  ").append(key).append(" needs ")
                .append(String.join(" ", targets)).append('\n'));
    }

    private static void section(StringBuilder out, String name, List<SlotInfo> slots) {
        if (slots.isEmpty()) {
            return;
        }
        out.append('\n').append(name).append('\n');
        for (SlotInfo slot : slots) {
            out.append("  ").append(slot.number()).append(' ').append(slot.text()).append('\n');
        }
    }

    /** Identical layers share one grid — except a layer with inline nodes, whose ids are its own. */
    private static List<List<Integer>> layerGroups(Blueprint bp) {
        Map<String, List<Integer>> groups = new LinkedHashMap<>();
        for (int layer = bp.baseMinLayer(); layer <= bp.baseMaxLayer(); layer++) {
            StringBuilder key = new StringBuilder();
            boolean nodes = false;
            for (int z = 0; z < bp.depth(); z++) {
                for (int x = 0; x < bp.width(); x++) {
                    char glyph = bp.glyph(layer, x, z);
                    nodes |= glyph == Blueprint.NODE_AIR;
                    key.append(glyph);
                }
                key.append('\n');
            }
            groups.computeIfAbsent(nodes ? "#" + layer : key.toString(), k -> new ArrayList<>()).add(layer);
        }
        return new ArrayList<>(groups.values());
    }

    /** {@code 0 2 6..8} — consecutive runs of three or more become a range. */
    static String indexSet(List<Integer> sorted) {
        List<String> parts = new ArrayList<>();
        int i = 0;
        while (i < sorted.size()) {
            int j = i;
            while (j + 1 < sorted.size() && sorted.get(j + 1) == sorted.get(j) + 1) {
                j++;
            }
            if (j - i >= 2) {
                parts.add(sorted.get(i) + ".." + sorted.get(j));
            } else {
                for (int k = i; k <= j; k++) {
                    parts.add(String.valueOf(sorted.get(k)));
                }
            }
            i = j + 1;
        }
        return String.join(" ", parts);
    }

    private static boolean hasOverlay(Blueprint bp, int layer) {
        return bp.nodes().values().stream().anyMatch(node -> !node.inline() && node.layer() == layer);
    }

    private static String ids(Blueprint bp, int layer, int z, boolean inline) {
        String ids = bp.nodes().values().stream()
                .filter(node -> node.inline() == inline && node.layer() == layer && node.z() == z)
                .sorted((a, b) -> Integer.compare(a.x(), b.x()))
                .map(Node::id).collect(Collectors.joining(" "));
        return ids.isEmpty() ? "" : "  < " + ids;
    }

    private static String pad(String s, int width) {
        return s.length() >= width ? s : s + " ".repeat(width - s.length());
    }
}
