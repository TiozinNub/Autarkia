package dev.luizloyola.autarkia.core.direction;

import dev.luizloyola.anima.core.brain.knowledge.Region;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.territory.ChunkKey;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * A party's HOME: which chunks of its area have been cleared. The area itself is the party's
 * territory, Anima's, and is the home — there is no yard, no single point standing for it
 * (docs/superpowers/specs/2026-10-01-home-area-design.md, decision 11). HOME is what the area means
 * to a party of Persons.
 *
 * <p>A chunk is cleared in two steps, its trees felled and then its plants pulled up, and only then
 * is it ready to build on.
 *
 * @param felled  the chunks whose felling has finished — all the party can know about the trees on
 *                them, since nobody shares what they merely walked past
 * @param cleared the chunks whose plants were pulled up after the felling: ready to build on. A
 *                chunk the area no longer holds may linger in either set; read them against the area.
 */
public record Home(SortedSet<ChunkKey> felled, SortedSet<ChunkKey> cleared) {

    public Home {
        felled = Collections.unmodifiableSortedSet(new TreeSet<>(felled));
        cleared = Collections.unmodifiableSortedSet(new TreeSet<>(cleared));
    }

    /**
     * How far work on a chunk reaches below and above the height it is posted at. The ground is
     * ground, but a felling must take in a whole tree standing on it, crown and all, and the ground
     * is rarely flat.
     */
    private static final int BELOW = 16;
    private static final int ABOVE = 48;

    /** A new HOME, nothing cleared. */
    public static Home fresh() {
        return new Home(new TreeSet<>(), new TreeSet<>());
    }

    /** The chunks a square of {@code radius} round {@code centre} touches: a new HOME's first claim. */
    public static SortedSet<ChunkKey> square(Pos centre, int radius) {
        return ChunkKey.covering(ChunkKey.OVERWORLD, centre.x() - radius, centre.z() - radius,
                centre.x() + radius, centre.z() + radius);
    }

    public Home withFelled(ChunkKey chunk) {
        SortedSet<ChunkKey> now = new TreeSet<>(felled);
        now.add(chunk);
        return new Home(now, cleared);
    }

    public Home withCleared(ChunkKey chunk) {
        SortedSet<ChunkKey> now = new TreeSet<>(cleared);
        now.add(chunk);
        return new Home(felled, now);
    }

    /** The chunks of {@code area} not yet cleared, in the area's order. */
    public List<ChunkKey> uncleared(Collection<ChunkKey> area) {
        List<ChunkKey> left = new ArrayList<>();
        for (ChunkKey chunk : area) {
            if (!cleared.contains(chunk)) {
                left.add(chunk);
            }
        }
        return left;
    }

    /** The chunks of {@code area} not yet cleared, nearest {@code from} first. */
    public List<ChunkKey> uncleared(Collection<ChunkKey> area, Pos from) {
        List<ChunkKey> left = uncleared(area);
        left.sort((a, b) -> Long.compare(distance(a, from), distance(b, from)));
        return left;
    }

    /** What work on this chunk covers: its columns, from below {@code y} to above it. */
    public static Region region(ChunkKey chunk, int y) {
        return new Region(new Pos(chunk.minBlockX(), y - BELOW, chunk.minBlockZ()),
                new Pos(chunk.maxBlockX(), y + ABOVE, chunk.maxBlockZ()));
    }

    /**
     * The chunk a felling's or a plant clearing's bounds are the columns of, if they are one. Read
     * by columns alone: the height work is posted at follows the party's stores, which move.
     */
    public static Optional<ChunkKey> chunkOf(Region bounds) {
        ChunkKey chunk = ChunkKey.at(ChunkKey.OVERWORLD, bounds.min().x(), bounds.min().z());
        return bounds.min().x() == chunk.minBlockX() && bounds.min().z() == chunk.minBlockZ()
                && bounds.max().x() == chunk.maxBlockX() && bounds.max().z() == chunk.maxBlockZ()
                ? Optional.of(chunk) : Optional.empty();
    }

    /**
     * An area as block rectangles {@code {minX, minZ, maxX, maxZ}}, a run of chunks along x merged
     * into one: what a box-shaped reader takes, at a fraction of one box a chunk.
     */
    public static List<int[]> rows(Set<ChunkKey> area) {
        List<int[]> rows = new ArrayList<>();
        int[] open = null;
        ChunkKey last = null;
        for (ChunkKey chunk : byRow(area)) {
            if (open != null && chunk.z() == last.z() && chunk.x() == last.x() + 1
                    && chunk.dimension().equals(last.dimension())) {
                open[2] = chunk.maxBlockX();
            } else {
                open = new int[] {chunk.minBlockX(), chunk.minBlockZ(), chunk.maxBlockX(), chunk.maxBlockZ()};
                rows.add(open);
            }
            last = chunk;
        }
        return rows;
    }

    private static List<ChunkKey> byRow(Set<ChunkKey> area) {
        List<ChunkKey> sorted = new ArrayList<>(area);
        sorted.sort((a, b) -> a.dimension().equals(b.dimension())
                ? (a.z() != b.z() ? Integer.compare(a.z(), b.z()) : Integer.compare(a.x(), b.x()))
                : a.dimension().compareTo(b.dimension()));
        return sorted;
    }

    private static long distance(ChunkKey chunk, Pos from) {
        long dx = chunk.minBlockX() + 8 - from.x();
        long dz = chunk.minBlockZ() + 8 - from.z();
        return dx * dx + dz * dz;
    }

    /**
     * The middle of an area: the chunk nearest the mean of its chunks, ties to the first in order.
     * Worked out when asked and never stored, so it moves as the area grows.
     */
    public static Optional<ChunkKey> middle(Collection<ChunkKey> area) {
        if (area.isEmpty()) {
            return Optional.empty();
        }
        double x = 0;
        double z = 0;
        for (ChunkKey chunk : area) {
            x += chunk.x();
            z += chunk.z();
        }
        double meanX = x / area.size();
        double meanZ = z / area.size();
        ChunkKey best = null;
        double bestDistance = Double.MAX_VALUE;
        for (ChunkKey chunk : new TreeSet<>(area)) {
            double d = (chunk.x() - meanX) * (chunk.x() - meanX) + (chunk.z() - meanZ) * (chunk.z() - meanZ);
            if (d < bestDistance) {
                best = chunk;
                bestDistance = d;
            }
        }
        return Optional.of(best);
    }
}
