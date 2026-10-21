package dev.luizloyola.autarkia.core.builder;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.knowledge.BlockKind;
import dev.luizloyola.anima.core.brain.knowledge.BlockProbe;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.PlaceFrom;
import dev.luizloyola.anima.core.brain.task.Standing;
import dev.luizloyola.anima.core.nav.MoveCapabilities;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * What a builder breaks out of a step's cell before placing there: what a player would just break
 * to build — leaves, plants, vines, a snow layer. Which blocks those are is vanilla's tags, read by
 * the mod layer ({@link #softBy}); a tree's own grown leaves are known here without it.
 *
 * <p>A break needs a clear arm, where a place needs only a face: from inside a hut, two roof cells
 * under a neighbour's canopy were in reach behind the roof, and their leaves stayed (2026-10-21). So
 * the stand is chosen for an arm path through nothing but air and grown leaves, broken on the way.
 */
public final class Clearance {

    /** The most leaves broken to clear the arm's way to one cell. */
    static final int MOST_IN_THE_WAY = 6;

    private static volatile Predicate<String> soft = id -> false;

    private Clearance() {
    }

    /** Sets which block ids a builder breaks out of a cell it builds in. */
    public static void softBy(Predicate<String> rule) {
        soft = rule;
    }

    /** Whether {@code cell} holds something a builder breaks to build there. */
    public static boolean soft(BlockProbe probe, Pos cell) {
        if (probe.empty(cell.x(), cell.y(), cell.z())) {
            return false;
        }
        return probe.at(cell.x(), cell.y(), cell.z()) == BlockKind.LEAVES
                || soft.test(probe.idAt(cell.x(), cell.y(), cell.z()));
    }

    /** A stand that reaches {@code cell}, and the leaves to break, nearest first, to clear the arm's way. */
    public record Way(Pos stand, List<Pos> inTheWay) {
    }

    /** The cheapest way to break {@code cell}: the nearest stand, fewest leaves in the way; null for none. */
    public static @Nullable Way to(BrainContext ctx, Pos cell, Set<Pos> also) {
        MoveCapabilities body = MoveCapabilities.of(ctx.profile());
        double eye = body.height() * Standing.EYE;
        BlockProbe probe = ctx.percepts().blocks();
        Pos here = ctx.percepts().position();
        int span = (int) Math.ceil(PlaceFrom.STAND_REACH);
        int below = (int) Math.ceil(PlaceFrom.STAND_REACH + eye);
        Way best = null;
        long bestScore = Long.MAX_VALUE;
        for (int y = cell.y() - below; y <= cell.y() + span; y++) {
            for (int z = cell.z() - span; z <= cell.z() + span; z++) {
                for (int x = cell.x() - span; x <= cell.x() + span; x++) {
                    Pos stand = new Pos(x, y, z);
                    if (!Standing.reaches(ctx.percepts().terrain(), body, stand, cell, also, PlaceFrom.STAND_REACH)) {
                        continue;
                    }
                    List<Pos> leaves = inTheWay(probe, stand, eye, cell);
                    if (leaves == null) {
                        continue;
                    }
                    long dx = x - here.x();
                    long dy = y - here.y();
                    long dz = z - here.z();
                    // A leaf broken costs about as much as four blocks walked.
                    long score = dx * dx + dy * dy + dz * dz + 16L * leaves.size();
                    if (score < bestScore) {
                        best = new Way(stand, leaves);
                        bestScore = score;
                    }
                }
            }
        }
        return best;
    }

    /**
     * The grown leaves between the eye at {@code stand} and the middle of {@code cell}, in the order
     * the arm meets them — the march the breaker refuses by; null when anything else is in the way.
     */
    static @Nullable List<Pos> inTheWay(BlockProbe probe, Pos stand, double eye, Pos cell) {
        double fx = stand.x() + 0.5;
        double fy = stand.y() + eye;
        double fz = stand.z() + 0.5;
        double tx = cell.x() + 0.5;
        double ty = cell.y() + 0.5;
        double tz = cell.z() + 0.5;
        double length = Math.sqrt((tx - fx) * (tx - fx) + (ty - fy) * (ty - fy) + (tz - fz) * (tz - fz));
        int steps = (int) Math.ceil(length * 2.0);
        List<Pos> leaves = new ArrayList<>();
        Set<Pos> seen = new HashSet<>();
        for (int i = 1; i < steps; i++) {
            double t = i / (double) steps;
            Pos at = new Pos((int) Math.floor(fx + (tx - fx) * t), (int) Math.floor(fy + (ty - fy) * t),
                    (int) Math.floor(fz + (tz - fz) * t));
            if (at.equals(cell) || !seen.add(at)) {
                continue;
            }
            BlockKind kind = probe.at(at.x(), at.y(), at.z());
            if (kind == BlockKind.AIR || kind == BlockKind.WATER) {
                continue;
            }
            // Only leaves that grew: placed ones may be somebody's building.
            if (kind != BlockKind.LEAVES || leaves.size() >= MOST_IN_THE_WAY) {
                return null;
            }
            leaves.add(at);
        }
        return leaves;
    }
}
