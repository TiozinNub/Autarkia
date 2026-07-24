package dev.luizloyola.autarkia.core.brain.task;

import dev.luizloyola.autarkia.core.brain.sense.Pos;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Groups scattered drops into "flocks" (decision: Luiz) so collection walks to cluster
 * centers instead of chasing single items: complete beats optimal — the walk-over pickup
 * vacuums whatever the path crosses, the caller re-scans after each leg, and stragglers form
 * their own one-item flocks until the ground is clean.
 */
public final class Flocks {
    /** Two drops within this chebyshev distance belong to the same flock. */
    public static final int FLOCK_RADIUS = 2;

    private Flocks() {
    }

    /** How many flocks the given drops form — the collection phase's lap-guard input. */
    public static int count(List<Pos> drops) {
        return clusters(drops).size();
    }

    /** The centroid of the flock nearest to {@code from}, or null when there are no drops. */
    public static Pos nearestCentroid(List<Pos> drops, Pos from) {
        Pos best = null;
        long bestDist = Long.MAX_VALUE;
        for (List<Pos> flock : clusters(drops)) {
            Pos centroid = centroid(flock);
            long dx = centroid.x() - from.x();
            long dy = centroid.y() - from.y();
            long dz = centroid.z() - from.z();
            long dist = dx * dx + dy * dy + dz * dz;
            if (dist < bestDist) {
                bestDist = dist;
                best = centroid;
            }
        }
        return best;
    }

    private static List<List<Pos>> clusters(List<Pos> drops) {
        List<List<Pos>> flocks = new ArrayList<>();
        Set<Pos> unvisited = new HashSet<>(drops);
        for (Pos seed : drops) {
            if (!unvisited.remove(seed)) {
                continue;
            }
            List<Pos> flock = new ArrayList<>();
            Deque<Pos> frontier = new ArrayDeque<>();
            frontier.add(seed);
            flock.add(seed);
            while (!frontier.isEmpty()) {
                Pos p = frontier.poll();
                for (Pos other : new ArrayList<>(unvisited)) {
                    if (Math.abs(other.x() - p.x()) <= FLOCK_RADIUS
                            && Math.abs(other.y() - p.y()) <= FLOCK_RADIUS
                            && Math.abs(other.z() - p.z()) <= FLOCK_RADIUS) {
                        unvisited.remove(other);
                        flock.add(other);
                        frontier.add(other);
                    }
                }
            }
            flocks.add(flock);
        }
        return flocks;
    }

    private static Pos centroid(List<Pos> flock) {
        int x = 0;
        int y = 0;
        int z = 0;
        for (Pos p : flock) {
            x += p.x();
            y += p.y();
            z += p.z();
        }
        int n = flock.size();
        return new Pos(Math.round((float) x / n), Math.round((float) y / n), Math.round((float) z / n));
    }
}
