package dev.luizloyola.autarkia.core.bp;

import dev.luizloyola.autarkia.core.bp.Blueprint.Edge;
import dev.luizloyola.autarkia.core.bp.Blueprint.EntryInfo;
import dev.luizloyola.autarkia.core.bp.Blueprint.Facing;
import dev.luizloyola.autarkia.core.bp.Blueprint.Node;
import dev.luizloyola.autarkia.core.bp.Diagnostic.Cell;
import dev.luizloyola.autarkia.core.bp.Dictionary.BlockInfo;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The warnings: things that load but are probably not what the author meant. Every one is a
 * report, never an error — a blueprint is allowed to be strange on purpose.
 */
public final class Checks {

    private Checks() {
    }

    public static void run(Blueprint bp, Dictionary dict, Diagnostics out) {
        Shape shape = new Shape(bp, dict);
        doors(shape, out);
        support(shape, out);
        falling(shape, out);
        closedRooms(shape, out);
        lonelyNodes(bp, out);
        openColumns(bp, out);
    }

    /**
     * A door is a panel across its facing axis, so both faces must open onto something walkable.
     * The worked house in the format spec had its door along its wall, which is why this exists.
     */
    static void doors(Shape shape, Diagnostics out) {
        Blueprint bp = shape.bp;
        for (int layer = bp.minLayer(); layer <= bp.maxLayer(); layer++) {
            for (int z = 0; z < bp.depth(); z++) {
                for (int x = 0; x < bp.width(); x++) {
                    EntryInfo entry = bp.entryAt(layer, x, z);
                    if (entry == null || !shape.all(entry, Shape::isDoor) || !isMainHalf(entry)) {
                        continue;
                    }
                    Facing facing = shape.facing(entry);
                    if (facing == null) {
                        continue;
                    }
                    boolean front = shape.solid(layer, x + facing.dx, z + facing.dz);
                    boolean back = shape.solid(layer, x - facing.dx, z - facing.dz);
                    if (!front && !back) {
                        continue;
                    }
                    Facing across = facing.clockwise();
                    boolean crossOpen = !shape.solid(layer, x + across.dx, z + across.dz)
                            && !shape.solid(layer, x - across.dx, z - across.dz);
                    String fix = crossOpen ? "; facing=" + across.word() + " or " + across.opposite().word()
                            + " fits the gap" : "";
                    out.cellReport("door_across_wall", bp.sourceLine(layer, x, z), bp.sourceColumn(layer, x, z),
                            new Cell(layer, x, z), "the door faces "
                            + facing.word() + ", so it opens onto a solid block " + (front && back ? "on both sides"
                            : front ? "in front" : "behind") + fix);
                }
            }
        }
    }

    private static boolean isMainHalf(EntryInfo entry) {
        return !"upper".equals(entry.outcomes().get(0).props().get("half"));
    }

    /**
     * Blocks with no chain of placed blocks down to the ground. The builder places only what stands
     * on the ground or on what it already placed, so these will be refused; better said at load.
     */
    static void support(Shape shape, Diagnostics out) {
        Blueprint bp = shape.bp;
        boolean[] held = new boolean[bp.layers() * bp.depth() * bp.width()];
        Deque<int[]> todo = new ArrayDeque<>();
        for (int layer = bp.minLayer(); layer <= Math.max(bp.minLayer(), 0) && layer <= bp.maxLayer(); layer++) {
            for (int z = 0; z < bp.depth(); z++) {
                for (int x = 0; x < bp.width(); x++) {
                    char glyph = bp.glyph(layer, x, z);
                    // Nothing drawn below layer 1 means the world's own ground is under it.
                    boolean ground = layer <= 0 ? glyph == Blueprint.TERRAIN || glyph == Blueprint.ANY
                            || shape.placed(layer, x, z) : shape.placed(layer, x, z);
                    if (ground) {
                        held[shape.index(layer, x, z)] = true;
                        todo.add(new int[] {layer, x, z});
                    }
                }
            }
        }
        shape.spread(todo, held, shape::placed);
        Set<Integer> reported = new HashSet<>();
        for (int layer = bp.minLayer(); layer <= bp.maxLayer(); layer++) {
            for (int z = 0; z < bp.depth(); z++) {
                for (int x = 0; x < bp.width(); x++) {
                    int i = shape.index(layer, x, z);
                    if (!shape.placed(layer, x, z) || held[i] || reported.contains(i)) {
                        continue;
                    }
                    boolean[] group = new boolean[held.length];
                    group[i] = true;
                    Deque<int[]> walk = new ArrayDeque<>();
                    walk.add(new int[] {layer, x, z});
                    shape.spread(walk, group, shape::placed);
                    int count = 0;
                    for (int j = 0; j < group.length; j++) {
                        if (group[j]) {
                            reported.add(j);
                            count++;
                        }
                    }
                    out.cellReport("unsupported", bp.sourceLine(layer, x, z), bp.sourceColumn(layer, x, z),
                            new Cell(layer, x, z),
                            count + " block" + (count == 1 ? "" : "s") + " here touch nothing that reaches the "
                                    + "ground; the builder will refuse to place them");
                }
            }
        }
    }

    /** Sand and gravel over air fall the moment they are placed. */
    static void falling(Shape shape, Diagnostics out) {
        Blueprint bp = shape.bp;
        for (EntryInfo entry : bp.legend().values()) {
            if (!shape.all(entry, BlockInfo::falls)) {
                continue;
            }
            int count = 0;
            int[] first = null;
            for (int layer = bp.minLayer() + 1; layer <= bp.maxLayer(); layer++) {
                for (int z = 0; z < bp.depth(); z++) {
                    for (int x = 0; x < bp.width(); x++) {
                        char below = bp.glyph(layer - 1, x, z);
                        if (bp.glyph(layer, x, z) == entry.glyph()
                                && (below == Blueprint.AIR || below == Blueprint.NODE_AIR)) {
                            count++;
                            first = first == null ? new int[] {layer, x, z} : first;
                        }
                    }
                }
            }
            if (first != null) {
                out.cellReport("falls", bp.sourceLine(first[0], first[1], first[2]), bp.sourceColumn(first[0], first[1],
                        first[2]), new Cell(first[0], first[1], first[2]), "'" + entry.glyph() + "' falls, and "
                        + count + " of its cells stand over air");
            }
        }
    }

    /** A space with room to stand and no door: nothing can walk in, and whoever builds it is shut in. */
    static void closedRooms(Shape shape, Diagnostics out) {
        for (List<int[]> room : shape.interiors()) {
            if (!Facts.headroom(shape, room) || touchesDoor(shape, room)) {
                continue;
            }
            int[] first = room.get(0);
            Blueprint bp = shape.bp;
            out.cellReport("no_way_in", bp.sourceLine(first[0], first[1], first[2]), bp.sourceColumn(first[0], first[1],
                    first[2]), new Cell(first[0], first[1], first[2]), "an enclosed space of "
                    + room.size() + " cells with room to stand and no door, gate or trapdoor");
        }
    }

    static boolean touchesDoor(Shape shape, List<int[]> room) {
        int[][] steps = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int[] cell : room) {
            for (int[] step : steps) {
                if (shape.closable(cell[0] + step[0], cell[1] + step[1], cell[2] + step[2])) {
                    return true;
                }
            }
        }
        return false;
    }

    static void lonelyNodes(Blueprint bp, Diagnostics out) {
        Set<String> joined = new HashSet<>();
        for (Edge edge : bp.edges()) {
            joined.add(edge.from());
            joined.add(edge.to());
        }
        for (Node node : bp.nodes().values()) {
            if (node.type().equals(NodeTypes.PATH_NODE) && !joined.contains(node.id())) {
                out.cellReport("lonely_node", node.line(), 1, new Cell(node.layer(), node.x(), node.z()),
                        "path node '" + node.id() + "' is on no path");
            }
        }
    }

    /**
     * A column that places nothing yet clears air: usually the margin of a box, where {@code ?} was
     * meant. Placing it would take out every tree and flower standing there.
     */
    static void openColumns(Blueprint bp, Diagnostics out) {
        List<Cell> open = new ArrayList<>();
        for (int z = 0; z < bp.depth(); z++) {
            for (int x = 0; x < bp.width(); x++) {
                boolean places = false;
                Cell clears = null;
                for (int layer = bp.minLayer(); layer <= bp.maxLayer(); layer++) {
                    char glyph = bp.glyph(layer, x, z);
                    places |= glyph == Blueprint.TERRAIN || bp.entryAt(layer, x, z) != null;
                    if (clears == null && glyph == Blueprint.AIR) {
                        clears = new Cell(layer, x, z);
                    }
                }
                if (!places && clears != null) {
                    open.add(clears);
                }
            }
        }
        if (!open.isEmpty()) {
            Cell first = open.get(0);
            out.cellReport("clears_open_column", bp.sourceLine(first.layer(), first.x(), first.z()),
                    bp.sourceColumn(first.layer(), first.x(), first.z()), first, open.size() + " column"
                    + (open.size() == 1 ? " places" : "s place") + " nothing but clear the air; '?' leaves the "
                    + "world as it is");
        }
    }
}
