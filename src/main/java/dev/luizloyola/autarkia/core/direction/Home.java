package dev.luizloyola.autarkia.core.direction;

import dev.luizloyola.anima.core.brain.knowledge.Region;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.territory.ChunkKey;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * A party's HOME: the yard its goods go to, and which chunks of its area have been cleared. The area
 * itself is the party's territory, Anima's (docs/superpowers/specs/2026-10-01-home-area-design.md);
 * HOME is what the area means to a party of Persons.
 *
 * <p>A chunk is cleared in two steps, its trees felled and then its plants pulled up, and only then
 * is it ready to build on.
 *
 * @param felled  the chunks whose felling has finished — all the party can know about the trees on
 *                them, since nobody shares what they merely walked past
 * @param cleared the chunks whose plants were pulled up after the felling: ready to build on. A
 *                chunk the area no longer holds may linger in either set; read them against the area.
 */
public record Home(Pos yard, SortedSet<ChunkKey> felled, SortedSet<ChunkKey> cleared) {

    public Home {
        Objects.requireNonNull(yard, "yard");
        felled = Collections.unmodifiableSortedSet(new TreeSet<>(felled));
        cleared = Collections.unmodifiableSortedSet(new TreeSet<>(cleared));
    }

    /**
     * How far work on a chunk reaches below and above the yard. The ground is ground, but a felling
     * must take in a whole tree standing on it, crown and all, and the ground is rarely flat.
     */
    private static final int BELOW = 16;
    private static final int ABOVE = 48;

    /** A new HOME, nothing cleared. */
    public static Home at(Pos yard) {
        return new Home(yard, new TreeSet<>(), new TreeSet<>());
    }

    /** The chunks a square of {@code radius} round {@code centre} touches: a new HOME's first claim. */
    public static SortedSet<ChunkKey> square(Pos centre, int radius) {
        return ChunkKey.covering(ChunkKey.OVERWORLD, centre.x() - radius, centre.z() - radius,
                centre.x() + radius, centre.z() + radius);
    }

    public Home withFelled(ChunkKey chunk) {
        SortedSet<ChunkKey> now = new TreeSet<>(felled);
        now.add(chunk);
        return new Home(yard, now, cleared);
    }

    public Home withCleared(ChunkKey chunk) {
        SortedSet<ChunkKey> now = new TreeSet<>(cleared);
        now.add(chunk);
        return new Home(yard, felled, now);
    }

    /** The chunks of {@code area} not yet cleared, nearest the yard first. */
    public List<ChunkKey> uncleared(Collection<ChunkKey> area) {
        List<ChunkKey> left = new ArrayList<>();
        for (ChunkKey chunk : area) {
            if (!cleared.contains(chunk)) {
                left.add(chunk);
            }
        }
        left.sort((a, b) -> Long.compare(distance(a), distance(b)));
        return left;
    }

    /** What work on this chunk covers: its columns, from below the yard to above it. */
    public Region region(ChunkKey chunk) {
        return new Region(new Pos(chunk.minBlockX(), yard.y() - BELOW, chunk.minBlockZ()),
                new Pos(chunk.maxBlockX(), yard.y() + ABOVE, chunk.maxBlockZ()));
    }

    /** The chunk a felling's or a plant clearing's bounds are this HOME's work on, if they are one. */
    public Optional<ChunkKey> chunkOf(Region bounds) {
        ChunkKey chunk = ChunkKey.at(ChunkKey.OVERWORLD, bounds.min().x(), bounds.min().z());
        return region(chunk).equals(bounds) ? Optional.of(chunk) : Optional.empty();
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

    private long distance(ChunkKey chunk) {
        long dx = chunk.minBlockX() + 8 - yard.x();
        long dz = chunk.minBlockZ() + 8 - yard.z();
        return dx * dx + dz * dz;
    }
}
