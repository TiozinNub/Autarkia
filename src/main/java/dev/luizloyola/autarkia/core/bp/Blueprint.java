package dev.luizloyola.autarkia.core.bp;

import dev.luizloyola.autarkia.core.bp.BpSource.Term;
import dev.luizloyola.autarkia.core.bp.Variants.Selection;
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
    private final int[][][] cellLine;
    private final int[][][] cellColumn;
    private final int baseMin;
    private final int baseMax;
    private final List<SlotInfo> slots;
    private final Map<Character, EntryInfo> legend;
    private final Map<String, Node> nodes;
    private final List<Edge> edges;
    private final Variants variants;
    private final Selection selection;

    /**
     * @param cellLine   where each cell was drawn, by {@code [layer - min][z][x]}; 0 for a layer no
     *                   base grid draws
     * @param baseMin    the base's own first layer; the box may reach past it where variants draw
     */
    Blueprint(String id, Headers headers, int width, int depth, int minLayer, char[][][] cells, int[][][] cellLine,
              int[][][] cellColumn, int baseMin, int baseMax, List<SlotInfo> slots, Map<Character, EntryInfo> legend,
              Map<String, Node> nodes, List<Edge> edges, Variants variants, Selection selection) {
        this.id = Objects.requireNonNull(id, "id");
        this.headers = headers;
        this.width = width;
        this.depth = depth;
        this.minLayer = minLayer;
        this.cells = cells;
        this.cellLine = cellLine;
        this.cellColumn = cellColumn;
        this.baseMin = baseMin;
        this.baseMax = baseMax;
        this.slots = List.copyOf(slots);
        this.legend = Collections.unmodifiableMap(new LinkedHashMap<>(legend));
        this.nodes = Collections.unmodifiableMap(new LinkedHashMap<>(nodes));
        this.edges = List.copyOf(edges);
        this.variants = variants;
        this.selection = selection;
    }

    /**
     * This blueprint with a selection's variants laid over its base: an ordinary blueprint, with no
     * variants left, that planning and the checks read as they read any other.
     */
    public Blueprint compose(Selection chosen) {
        if (variants.isEmpty()) {
            return this;
        }
        Selection canonical = variants.canonical(chosen);
        Variants.Composed composed = Variants.compose(cells, cellLine, cellColumn, variants.ordered(canonical));
        return new Blueprint(id, headers, width, depth, minLayer, composed.cells(), composed.line(),
                composed.column(), minLayer, maxLayer(), slots, legend, nodes, edges, Variants.NONE, canonical);
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

    /**
     * The source line that drew a cell — a variant's grid where the variant changed it, and 0 where
     * nothing drew it.
     */
    public int sourceLine(int layer, int x, int z) {
        return cellLine[layer - minLayer][z][x];
    }

    public int sourceColumn(int layer, int x, int z) {
        return cellColumn[layer - minLayer][z][x];
    }

    /** The layers the base itself draws; the box reaches past them only where a variant draws. */
    public int baseMinLayer() {
        return baseMin;
    }

    public int baseMaxLayer() {
        return baseMax;
    }

    /** The file's groups; none once composed. */
    public Variants variants() {
        return variants;
    }

    /** The selection this was composed from; {@link Selection#BASE} for the file itself. */
    public Selection selection() {
        return selection;
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
