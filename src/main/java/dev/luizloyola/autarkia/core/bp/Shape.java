package dev.luizloyola.autarkia.core.bp;

import dev.luizloyola.autarkia.core.bp.Blueprint.EntryInfo;
import dev.luizloyola.autarkia.core.bp.Blueprint.Facing;
import dev.luizloyola.autarkia.core.bp.Blueprint.Outcome;
import dev.luizloyola.autarkia.core.bp.Dictionary.BlockInfo;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * What a blueprint's cells are physically — solid, passable, a door — and the flood fills the checks
 * and the facts share. An entry is solid only when every block it can come out as is; a cell a
 * {@code mix} might make either way is not a wall anyone can count on.
 */
final class Shape {

    final Blueprint bp;
    private final Dictionary dict;
    private final int layers;
    /** Index = (layer - min) * depth * width + z * width + x. */
    private final boolean[] solid;
    private final boolean[] placed;
    private final boolean[] closable;
    private final boolean[] exterior;

    Shape(Blueprint bp, Dictionary dict) {
        this.bp = bp;
        this.dict = dict;
        this.layers = bp.layers();
        int cells = layers * bp.depth() * bp.width();
        solid = new boolean[cells];
        placed = new boolean[cells];
        closable = new boolean[cells];
        for (int layer = bp.minLayer(); layer <= bp.maxLayer(); layer++) {
            for (int z = 0; z < bp.depth(); z++) {
                for (int x = 0; x < bp.width(); x++) {
                    int i = index(layer, x, z);
                    char glyph = bp.glyph(layer, x, z);
                    EntryInfo entry = bp.entryAt(layer, x, z);
                    placed[i] = entry != null;
                    solid[i] = glyph == Blueprint.TERRAIN || entry != null && all(entry, BlockInfo::solid);
                    closable[i] = entry != null && all(entry, Shape::isClosable);
                }
            }
        }
        // A door drawn by its lower half still fills the cell above; left as air, the flood would
        // walk in over the top of every door.
        for (int layer = bp.minLayer(); layer < bp.maxLayer(); layer++) {
            for (int z = 0; z < bp.depth(); z++) {
                for (int x = 0; x < bp.width(); x++) {
                    EntryInfo entry = bp.entryAt(layer, x, z);
                    if (entry != null && all(entry, Shape::isDoor)
                            && !"upper".equals(entry.outcomes().get(0).props().get("half"))
                            && bp.entryAt(layer + 1, x, z) == null) {
                        closable[index(layer + 1, x, z)] = true;
                    }
                }
            }
        }
        exterior = flood();
    }

    int index(int layer, int x, int z) {
        return ((layer - bp.minLayer()) * bp.depth() + z) * bp.width() + x;
    }

    boolean inside(int layer, int x, int z) {
        return bp.contains(layer, x, z);
    }

    boolean solid(int layer, int x, int z) {
        return inside(layer, x, z) && solid[index(layer, x, z)];
    }

    boolean placed(int layer, int x, int z) {
        return inside(layer, x, z) && placed[index(layer, x, z)];
    }

    boolean closable(int layer, int x, int z) {
        return inside(layer, x, z) && closable[index(layer, x, z)];
    }

    /**
     * Air a body could move through: air, {@code @}, {@code ?} above ground (it might be anything,
     * so assume open), and blocks with no collision that are not doors. Outside the box, and
     * {@code ?} in it, open above the ground and earth below it — the world as it stands.
     */
    boolean passable(int layer, int x, int z) {
        if (!inside(layer, x, z)) {
            return layer >= 1;
        }
        if (layer <= 0 && bp.glyph(layer, x, z) == Blueprint.ANY) {
            return false;
        }
        int i = index(layer, x, z);
        return !solid[i] && !closable[i];
    }

    /** Reached from outside the box through passable cells, with every door shut. */
    boolean exterior(int layer, int x, int z) {
        return !inside(layer, x, z) ? layer >= 1 : exterior[index(layer, x, z)];
    }

    boolean all(EntryInfo entry, Predicate<BlockInfo> test) {
        if (entry.outcomes().isEmpty()) {
            return false;
        }
        for (Outcome outcome : entry.outcomes()) {
            BlockInfo info = dict.block(outcome.block()).orElse(null);
            if (info == null || !test.test(info)) {
                return false;
            }
        }
        return true;
    }

    @Nullable BlockInfo first(EntryInfo entry) {
        return entry.outcomes().isEmpty() ? null : dict.block(entry.outcomes().get(0).block()).orElse(null);
    }

    /** Doors, fence gates, trapdoors: shut, they close a gap; open, they are the way in. */
    static boolean isClosable(BlockInfo info) {
        return info.properties().containsKey("open") && (info.properties().containsKey("hinge")
                || info.properties().containsKey("in_wall") || info.id().endsWith("_trapdoor"));
    }

    static boolean isDoor(BlockInfo info) {
        return info.properties().containsKey("hinge") && info.properties().containsKey("half");
    }

    /** The {@code facing} a cell's entry states, else its block's default; null if it has none. */
    @Nullable Facing facing(EntryInfo entry) {
        BlockInfo info = first(entry);
        if (info == null || !info.properties().containsKey("facing")) {
            return null;
        }
        String word = entry.outcomes().get(0).props().get("facing");
        return Facing.of(word != null ? word : info.defaults().getOrDefault("facing", "north"));
    }

    /**
     * The cells of the box the outside air reaches. Seeded from every passable cell on the box's
     * faces at or above ground — the ground itself is earth, and a cellar is not outdoors.
     */
    private boolean[] flood() {
        boolean[] seen = new boolean[solid.length];
        Deque<int[]> todo = new ArrayDeque<>();
        for (int layer = bp.minLayer(); layer <= bp.maxLayer(); layer++) {
            for (int z = 0; z < bp.depth(); z++) {
                for (int x = 0; x < bp.width(); x++) {
                    boolean face = x == 0 || z == 0 || x == bp.width() - 1 || z == bp.depth() - 1
                            || layer == bp.maxLayer();
                    if (face && layer >= 1 && passable(layer, x, z)) {
                        seen[index(layer, x, z)] = true;
                        todo.add(new int[] {layer, x, z});
                    }
                }
            }
        }
        spread(todo, seen, this::passable);
        return seen;
    }

    interface CellTest {
        boolean test(int layer, int x, int z);
    }

    /** Six-neighbour flood from {@code todo} over cells passing {@code through}, marking {@code seen}. */
    void spread(Deque<int[]> todo, boolean[] seen, CellTest through) {
        int[][] steps = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        while (!todo.isEmpty()) {
            int[] cell = todo.poll();
            for (int[] step : steps) {
                int layer = cell[0] + step[0];
                int x = cell[1] + step[1];
                int z = cell[2] + step[2];
                if (!inside(layer, x, z) || seen[index(layer, x, z)] || !through.test(layer, x, z)) {
                    continue;
                }
                seen[index(layer, x, z)] = true;
                todo.add(new int[] {layer, x, z});
            }
        }
    }

    /** The passable cells the outside never reaches, as connected groups — rooms, or voids in a wall. */
    List<List<int[]>> interiors() {
        boolean[] seen = exterior.clone();
        List<List<int[]>> groups = new ArrayList<>();
        for (int layer = bp.minLayer(); layer <= bp.maxLayer(); layer++) {
            for (int z = 0; z < bp.depth(); z++) {
                for (int x = 0; x < bp.width(); x++) {
                    if (seen[index(layer, x, z)] || !passable(layer, x, z)) {
                        continue;
                    }
                    seen[index(layer, x, z)] = true;
                    Deque<int[]> todo = new ArrayDeque<>();
                    todo.add(new int[] {layer, x, z});
                    List<int[]> group = new ArrayList<>(todo);
                    spread(todo, seen, (l, xx, zz) -> {
                        boolean joins = passable(l, xx, zz);
                        if (joins) {
                            group.add(new int[] {l, xx, zz});
                        }
                        return joins;
                    });
                    groups.add(group);
                }
            }
        }
        return groups;
    }
}
