package dev.luizloyola.autarkia.core.bp;

import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.autarkia.core.bp.Blueprint.Outcome;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * A blueprint with every choice made: each cell a concrete block or one of air, keep and terrain.
 * It copies what it was planned from, so editing the file and reloading cannot change a plan
 * already made (decision: Luiz).
 *
 * <p>Cells are in the drawing's coordinates; {@link #forEach} hands them out placed.
 */
public final class BuildPlan {

    public enum CellKind {
        /** Clear whatever is there. */
        AIR,
        /** {@code ?}: leave the world as it is. */
        KEEP,
        /** {@code ~}: natural ground, filled from the surroundings. */
        TERRAIN,
        BLOCK
    }

    /**
     * What the gatherers fetch for some cells: one item, or any of several where a mix leaves it
     * open (decision: Luiz — a mix's bill stays loose).
     */
    public record BillLine(Set<String> items, int count) {
        public BillLine {
            items = Collections.unmodifiableSet(new TreeSet<>(items));
        }

        /** The board's own vocabulary, so a bill posts with no adapter. */
        public ItemSpec spec() {
            return ItemSpec.anyOf(items);
        }
    }

    public interface CellVisitor {
        /** {@code dx} and {@code dz} are from the anchor; {@code layer} is also the height above it. */
        void visit(int dx, int layer, int dz, CellKind kind, @Nullable Outcome state);
    }

    private final String id;
    private final int version;
    private final int width;
    private final int depth;
    private final int minLayer;
    private final int layers;
    private final CellKind[] kinds;
    private final @Nullable Outcome[] states;
    private final Map<Integer, String> bindings;
    private final Map<String, String> variants;
    private final List<BillLine> bill;
    private final SortedMap<String, Integer> itemless;

    BuildPlan(String id, int version, int width, int depth, int minLayer, int layers, CellKind[] kinds,
              @Nullable Outcome[] states, Map<Integer, String> bindings, Map<String, String> variants,
              List<BillLine> bill, Map<String, Integer> itemless) {
        this.id = id;
        this.version = version;
        this.width = width;
        this.depth = depth;
        this.minLayer = minLayer;
        this.layers = layers;
        this.kinds = kinds;
        this.states = states;
        this.bindings = Collections.unmodifiableMap(new LinkedHashMap<>(bindings));
        this.variants = Collections.unmodifiableMap(new LinkedHashMap<>(variants));
        this.bill = List.copyOf(bill);
        this.itemless = Collections.unmodifiableSortedMap(new TreeMap<>(itemless));
    }

    public String id() {
        return id;
    }

    /** The blueprint's {@code version} header when planned, so a reader can tell a plan is from v1 of a v3 file. */
    public int version() {
        return version;
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
        return minLayer + layers - 1;
    }

    public CellKind kind(int layer, int x, int z) {
        return kinds[index(layer, x, z)];
    }

    /** The block a cell places, or null when it places none. */
    public @Nullable Outcome state(int layer, int x, int z) {
        return states[index(layer, x, z)];
    }

    /**
     * Every slot that settled, by number in file order, as a pin would write it back. A mix left
     * unpinned never settles and is absent.
     */
    public Map<Integer, String> bindings() {
        return bindings;
    }

    /**
     * Each group's variant, or {@code none} for an optional group left out, in the order the file
     * lists its groups — what a builder records, and what a pin would say to plan it again.
     */
    public Map<String, String> variants() {
        return variants;
    }

    /** The plan's choices as the pins that ask for them again: variants first, then slots. */
    public String pins() {
        List<String> words = new ArrayList<>();
        variants.forEach((group, variant) -> words.add(group + "=" + variant));
        bindings.forEach((slot, value) -> words.add(slot + "=" + value));
        return String.join(" ", words);
    }

    /** The planned bill, largest first. */
    public List<BillLine> bill() {
        return bill;
    }

    /** Blocks placed that no item places — water, fire — by id, with counts. */
    public SortedMap<String, Integer> itemless() {
        return itemless;
    }

    /** Every cell, placed: turned, mirrored and offset from the anchor. */
    public void forEach(Placement placement, CellVisitor visitor) {
        int placedWidth = placement.width(width, depth);
        int placedDepth = placement.depth(width, depth);
        for (int layer = minLayer; layer <= maxLayer(); layer++) {
            for (int z = 0; z < depth; z++) {
                for (int x = 0; x < width; x++) {
                    int[] cell = placement.cell(x, z, width, depth);
                    int i = index(layer, x, z);
                    visitor.visit(Placement.offset(cell[0], placedWidth), layer, Placement.offset(cell[1], placedDepth),
                            kinds[i], states[i]);
                }
            }
        }
    }

    private int index(int layer, int x, int z) {
        return ((layer - minLayer) * depth + z) * width + x;
    }
}
