package dev.luizloyola.autarkia.compat.bp;

import dev.luizloyola.autarkia.core.bp.Blueprint.Outcome;
import dev.luizloyola.autarkia.core.bp.Capture;
import dev.luizloyola.autarkia.core.bp.Dictionary;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalInt;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jspecify.annotations.Nullable;

/**
 * A box of the world read into a capture's cells, a slice per tick so a large box never stalls the
 * server (capture spec). Each state is normalised as it is read; a chunk that unloads mid-read stops
 * the read rather than leaving holes.
 */
public final class BoxReader {

    /** The backstop against a mistyped corner — a constant, not a knob (reader spec). */
    public static final int MAX_CELLS = 128 * 128 * 128;

    private final ServerLevel level;
    private final Dictionary dict;
    private final int minX;
    private final int minY;
    private final int minZ;
    private final int width;
    private final int depth;
    private final int height;
    private final int groundY;
    private final Outcome[][][] cells;
    private int next;
    private @Nullable String failure;

    private BoxReader(ServerLevel level, Dictionary dict, BlockPos min, BlockPos max, int groundY) {
        this.level = level;
        this.dict = dict;
        this.minX = min.getX();
        this.minY = min.getY();
        this.minZ = min.getZ();
        this.width = max.getX() - min.getX() + 1;
        this.depth = max.getZ() - min.getZ() + 1;
        this.height = max.getY() - min.getY() + 1;
        this.groundY = groundY;
        this.cells = new Outcome[height][depth][width];
    }

    /** A read of the box between two corners, layer 0 at {@code groundY}; the reason when it cannot start. */
    public static Started start(ServerLevel level, Dictionary dict, BlockPos a, BlockPos b, int groundY) {
        BlockPos min = new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()),
                Math.min(a.getZ(), b.getZ()));
        BlockPos max = new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()),
                Math.max(a.getZ(), b.getZ()));
        long volume = (long) (max.getX() - min.getX() + 1) * (max.getY() - min.getY() + 1)
                * (max.getZ() - min.getZ() + 1);
        if (volume > MAX_CELLS) {
            return new Started(null, "the box holds " + volume + " cells, past the " + MAX_CELLS + " a capture reads");
        }
        BoxReader reader = new BoxReader(level, dict, min, max, groundY);
        String unloaded = reader.unloaded();
        return unloaded == null ? new Started(reader, null) : new Started(null, unloaded);
    }

    /** @param reason why there is no reader */
    public record Started(@Nullable BoxReader reader, @Nullable String reason) {
    }

    public int cellCount() {
        return width * depth * height;
    }

    /** Reads up to {@code budget} cells; true once the box is read or the read has failed. */
    public boolean step(int budget) {
        if (failure != null || next >= cellCount()) {
            return true;
        }
        failure = unloaded();
        if (failure != null) {
            return true;
        }
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        int end = Math.min(cellCount(), next + budget);
        for (; next < end; next++) {
            int layer = next / (width * depth);
            int z = next / width % depth;
            int x = next % width;
            BlockState state = level.getBlockState(at.set(minX + x, minY + layer, minZ + z));
            Map<String, String> props = new LinkedHashMap<>();
            for (Property<?> property : state.getProperties()) {
                props.put(property.getName(), name(property, state.getValue(property)));
            }
            cells[layer][z][x] = Capture.normalise(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(), props,
                    dict);
        }
        return next >= cellCount();
    }

    public @Nullable String failure() {
        return failure;
    }

    /** The cells read, layer 0 at the ground the read was started with. */
    public Capture.Box box() {
        return new Capture.Box(width, depth, minY - groundY, cells);
    }

    private @Nullable String unloaded() {
        for (int cx = minX >> 4; cx <= (minX + width - 1) >> 4; cx++) {
            for (int cz = minZ >> 4; cz <= (minZ + depth - 1) >> 4; cz++) {
                if (!level.hasChunk(cx, cz)) {
                    return "chunk " + cx + ", " + cz + " is not loaded";
                }
            }
        }
        return null;
    }

    /**
     * The ground a box stands on, read from the columns just outside it: their commonest surface
     * height (reader spec), so a cellar dug under the box falls out as negative layers. Empty when
     * none of those columns is loaded.
     */
    public static OptionalInt groundAround(ServerLevel level, BlockPos a, BlockPos b) {
        int minX = Math.min(a.getX(), b.getX()) - 1;
        int maxX = Math.max(a.getX(), b.getX()) + 1;
        int minZ = Math.min(a.getZ(), b.getZ()) - 1;
        int maxZ = Math.max(a.getZ(), b.getZ()) + 1;
        Map<Integer, Integer> heights = new HashMap<>();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                boolean ring = x == minX || x == maxX || z == minZ || z == maxZ;
                if (ring && level.hasChunk(x >> 4, z >> 4)) {
                    int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                    heights.merge(surface, 1, Integer::sum);
                }
            }
        }
        return heights.entrySet().stream().max(Map.Entry.<Integer, Integer>comparingByValue()
                .thenComparing(Map.Entry.comparingByKey())).map(e -> OptionalInt.of(e.getKey()))
                .orElse(OptionalInt.empty());
    }

    @SuppressWarnings("unchecked")
    private static <T extends Comparable<T>> String name(Property<T> property, Object value) {
        return property.getName((T) value);
    }
}
