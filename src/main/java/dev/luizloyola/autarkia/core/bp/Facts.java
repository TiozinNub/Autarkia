package dev.luizloyola.autarkia.core.bp;

import dev.luizloyola.anima.core.craft.Workbench;
import dev.luizloyola.anima.core.store.Store;
import dev.luizloyola.autarkia.core.bp.Blueprint.EntryInfo;
import dev.luizloyola.autarkia.core.bp.Blueprint.Facing;
import dev.luizloyola.autarkia.core.bp.Blueprint.Outcome;
import dev.luizloyola.autarkia.core.bp.Diagnostic.Cell;
import dev.luizloyola.autarkia.core.bp.Dictionary.BlockInfo;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * What a blueprint is, worked out from its grid rather than declared: how big, what it holds, how
 * you get in. A Direction asking for "a built home" can read these instead of a {@code kind house}
 * header nobody could check.
 *
 * @param counts   placed cells per legend glyph — the unplanned bill, before materials are chosen
 * @param rooms    enclosed spaces with room to stand, doors shut
 * @param roofed   open cells at or above layer 1 with a solid block somewhere over them
 * @param stations the Anima place kinds among its blocks, by count
 */
public record Facts(int width, int depth, int minLayer, int maxLayer, int placed, Map<Character, Integer> counts,
                    List<Entrance> entrances, int beds, int rooms, int roomCells, int roofed,
                    Map<String, Integer> stations, int lights) {

    public Facts {
        counts = Map.copyOf(counts);
        entrances = List.copyOf(entrances);
        stations = Map.copyOf(stations);
    }

    /**
     * A door with a roof over one face and open sky over the other; {@code outward} is the open side.
     * Read off the roof rather than the air, so a window left open does not stop a front door being one.
     */
    public record Entrance(Cell cell, Facing outward) {
    }

    /** An entry that cannot be built until a node is reached: any one of {@code nodes} opens it. */
    public record Need(char glyph, Set<String> nodes) {
        public Need {
            nodes = Set.copyOf(nodes);
        }
    }

    private static final Map<String, String> STATIONS = Map.of(Workbench.ITEM_ID, Workbench.POI.key(),
            Store.ITEM_ID, Store.POI.key());

    public static Facts of(Blueprint bp, Dictionary dict) {
        Shape shape = new Shape(bp, dict);
        Map<Character, Integer> counts = new TreeMap<>();
        Map<String, Integer> stations = new TreeMap<>();
        List<Entrance> entrances = new ArrayList<>();
        int placed = 0;
        int beds = 0;
        int roofed = 0;
        int lights = 0;
        for (int layer = bp.minLayer(); layer <= bp.maxLayer(); layer++) {
            for (int z = 0; z < bp.depth(); z++) {
                for (int x = 0; x < bp.width(); x++) {
                    EntryInfo entry = bp.entryAt(layer, x, z);
                    if (entry == null) {
                        if (layer >= 1 && shape.passable(layer, x, z) && covered(shape, layer, x, z)) {
                            roofed++;
                        }
                        continue;
                    }
                    placed++;
                    counts.merge(entry.glyph(), 1, Integer::sum);
                    BlockInfo info = shape.first(entry);
                    if (info == null) {
                        continue;
                    }
                    if (shape.all(entry, block -> block.light() > 0)) {
                        lights++;
                    }
                    String station = STATIONS.get(info.id());
                    if (station != null && shape.all(entry, block -> block.id().equals(info.id()))) {
                        stations.merge(station, 1, Integer::sum);
                    }
                    if (isBedHead(entry, info)) {
                        beds++;
                    }
                    Entrance entrance = entrance(shape, entry, layer, x, z);
                    if (entrance != null) {
                        entrances.add(entrance);
                    }
                }
            }
        }
        int rooms = 0;
        int roomCells = 0;
        for (List<int[]> room : shape.interiors()) {
            if (headroom(shape, room)) {
                rooms++;
                roomCells += room.size();
            }
        }
        return new Facts(bp.width(), bp.depth(), bp.minLayer(), bp.maxLayer(), placed, counts, entrances, beds,
                rooms, roomCells, roofed, stations, lights);
    }

    /**
     * Which entries a party could not build yet, given which node opens each item. An entry is
     * buildable when any block it can come out as is — the planner chooses among what is allowed.
     */
    public static List<Need> needs(Blueprint bp, Dictionary dict, Function<String, Optional<String>> nodeOpening) {
        List<Need> needs = new ArrayList<>();
        for (EntryInfo entry : bp.legend().values()) {
            Set<String> nodes = new TreeSet<>();
            boolean free = false;
            for (Outcome outcome : entry.outcomes()) {
                String item = dict.block(outcome.block()).map(BlockInfo::item).orElse("");
                Optional<String> node = item.isEmpty() ? Optional.empty() : nodeOpening.apply(item);
                if (node.isPresent()) {
                    nodes.add(node.get());
                } else {
                    free = true;
                }
            }
            if (!free && !nodes.isEmpty()) {
                needs.add(new Need(entry.glyph(), nodes));
            }
        }
        return needs;
    }

    /** Room to stand: some cell of it has another of it straight above. */
    static boolean headroom(Shape shape, List<int[]> room) {
        Set<Long> cells = new HashSet<>();
        for (int[] cell : room) {
            cells.add(key(cell[0], cell[1], cell[2]));
        }
        for (int[] cell : room) {
            if (cells.contains(key(cell[0] + 1, cell[1], cell[2]))) {
                return true;
            }
        }
        return false;
    }

    private static long key(int layer, int x, int z) {
        return ((long) layer << 40) ^ ((long) x << 20) ^ z;
    }

    private static boolean covered(Shape shape, int layer, int x, int z) {
        if (!shape.inside(layer, x, z)) {
            return false;
        }
        for (int above = layer + 1; above <= shape.bp.maxLayer(); above++) {
            if (shape.solid(above, x, z)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isBedHead(EntryInfo entry, BlockInfo info) {
        return info.properties().containsKey("part") && info.properties().containsKey("facing")
                && !"foot".equals(entry.outcomes().get(0).props().get("part"));
    }

    private static @Nullable Entrance entrance(Shape shape, EntryInfo entry, int layer, int x, int z) {
        if (!shape.all(entry, Shape::isDoor) || "upper".equals(entry.outcomes().get(0).props().get("half"))) {
            return null;
        }
        Facing facing = shape.facing(entry);
        if (facing == null) {
            return null;
        }
        boolean frontCovered = covered(shape, layer, x + facing.dx, z + facing.dz);
        boolean backCovered = covered(shape, layer, x - facing.dx, z - facing.dz);
        if (frontCovered == backCovered) {
            return null;
        }
        return new Entrance(new Cell(layer, x, z), frontCovered ? facing.opposite() : facing);
    }

    /** Stable map order for a readout: glyphs in legend order. */
    public Map<Character, Integer> countsInLegendOrder(Blueprint bp) {
        Map<Character, Integer> ordered = new LinkedHashMap<>();
        for (Character glyph : bp.legend().keySet()) {
            ordered.put(glyph, counts.getOrDefault(glyph, 0));
        }
        return ordered;
    }
}
