package dev.luizloyola.autarkia.core.bp;

import dev.luizloyola.autarkia.core.bp.BpSource.Term;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * A bound blueprint: every glyph declared, every slot typed and narrowed, every node placed. What
 * a planner will choose from, and what the checks and facts read.
 *
 * <p>Cells are addressed in the drawing's coordinates — x west→east, z north→south, the layer as
 * numbered, 1 being where feet go.
 */
public final class Blueprint {

    public static final char AIR = '.';
    public static final char ANY = '?';
    public static final char TERRAIN = '~';
    public static final char NODE_AIR = '@';

    public enum Facing {
        NORTH(0, -1), EAST(1, 0), SOUTH(0, 1), WEST(-1, 0);

        public final int dx;
        public final int dz;

        Facing(int dx, int dz) {
            this.dx = dx;
            this.dz = dz;
        }

        public Facing opposite() {
            return values()[(ordinal() + 2) % 4];
        }

        public Facing clockwise() {
            return values()[(ordinal() + 1) % 4];
        }

        public String word() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }

        public static @Nullable Facing of(String word) {
            for (Facing facing : values()) {
                if (facing.word().equals(word)) {
                    return facing;
                }
            }
            return null;
        }
    }

    public record Headers(String name, String author, int version, Set<Facing> orientation, boolean flippable) {
        public Headers {
            orientation = Set.copyOf(orientation);
        }
    }

    public enum SlotKind { MATERIAL, PALETTE }

    /** A block with the properties its term wrote. */
    public record Outcome(String block, Map<String, String> props) {
        public Outcome {
            props = Collections.unmodifiableSortedMap(new TreeMap<>(props));
        }
    }

    /**
     * @param declared what the file named, before narrowing: leaf materials, or block ids
     * @param domain   what survives narrowing — the choices a planner is given
     * @param forms    for a material slot, every form the file asks of it
     */
    public record SlotInfo(int number, SlotKind kind, Binding binding, String text, List<Term> terms,
                           Set<String> declared, Set<String> domain, Set<String> forms, List<Outcome> outcomes,
                           int line) {
        public SlotInfo {
            terms = List.copyOf(terms);
            declared = Set.copyOf(declared);
            domain = Set.copyOf(domain);
            forms = Set.copyOf(forms);
            outcomes = List.copyOf(outcomes);
        }
    }

    /** A legend entry and every block it can come out as. */
    public record EntryInfo(char glyph, @Nullable Binding binding, String text, List<Term> terms,
                            List<Outcome> outcomes, int line) {
        public EntryInfo {
            terms = List.copyOf(terms);
            outcomes = List.copyOf(outcomes);
        }
    }

    public record Node(String id, String type, int layer, int x, int z, boolean inline, int line) {
    }

    public record Edge(String from, String to, int line) {
    }

    private final String id;
    private final Headers headers;
    private final int width;
    private final int depth;
    private final int minLayer;
    private final char[][][] cells;
    private final int[][] rowLine;
    private final int[][] rowColumn;
    private final List<SlotInfo> slots;
    private final Map<Character, EntryInfo> legend;
    private final Map<String, Node> nodes;
    private final List<Edge> edges;

    Blueprint(String id, Headers headers, int width, int depth, int minLayer, char[][][] cells, int[][] rowLine,
              int[][] rowColumn, List<SlotInfo> slots, Map<Character, EntryInfo> legend, Map<String, Node> nodes,
              List<Edge> edges) {
        this.id = Objects.requireNonNull(id, "id");
        this.headers = headers;
        this.width = width;
        this.depth = depth;
        this.minLayer = minLayer;
        this.cells = cells;
        this.rowLine = rowLine;
        this.rowColumn = rowColumn;
        this.slots = List.copyOf(slots);
        this.legend = Collections.unmodifiableMap(new LinkedHashMap<>(legend));
        this.nodes = Collections.unmodifiableMap(new LinkedHashMap<>(nodes));
        this.edges = List.copyOf(edges);
    }

    public String id() {
        return id;
    }

    public Headers headers() {
        return headers;
    }

    public int width() {
        return width;
    }

    public int depth() {
        return depth;
    }

    public int minLayer() {
        return minLayer;
    }

    public int maxLayer() {
        return minLayer + cells.length - 1;
    }

    public int layers() {
        return cells.length;
    }

    public boolean contains(int layer, int x, int z) {
        return layer >= minLayer && layer <= maxLayer() && x >= 0 && x < width && z >= 0 && z < depth;
    }

    /** The glyph drawn at a cell: a legend key, or one of {@code . ? ~ @}. */
    public char glyph(int layer, int x, int z) {
        return cells[layer - minLayer][z][x];
    }

    /** The legend entry drawn at a cell, or null for air, {@code ?}, {@code ~} and {@code @}. */
    public @Nullable EntryInfo entryAt(int layer, int x, int z) {
        return legend.get(glyph(layer, x, z));
    }

    /** The source line of the row that drew a cell — a layer drawn by a shared grid shares its lines. */
    public int sourceLine(int layer, int z) {
        return rowLine[layer - minLayer][z];
    }

    public int sourceColumn(int layer, int x, int z) {
        return rowColumn[layer - minLayer][z] + x;
    }

    public List<SlotInfo> slots() {
        return slots;
    }

    public Map<Character, EntryInfo> legend() {
        return legend;
    }

    public Map<String, Node> nodes() {
        return nodes;
    }

    public List<Edge> edges() {
        return edges;
    }
}
