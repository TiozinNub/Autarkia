package dev.luizloyola.autarkia.core.brain.knowledge;

import dev.luizloyola.autarkia.core.brain.sense.Pos;
import java.util.ArrayList;
import java.util.List;

/**
 * The "notice as you go" trigger geometry: the columns that <em>newly came into sense range</em> as
 * the person moved — the leading crescent. Nothing is emitted until the feet cell changes, so
 * standing costs zero; one block of movement sweeps ≈ 2R columns, and a discontinuity (first
 * sighting, teleport, any jump beyond R) yields the full disc.
 */
public final class CrescentSampler {
    /** Horizontal sense radius in blocks; kept inside the threat-scan scale of 16. */
    public static final int RADIUS = 12;

    private static final int RADIUS_SQ = RADIUS * RADIUS;

    private Column center;

    /** The newly-in-range columns; empty until the feet cell changes. */
    public List<Column> advance(Pos feet) {
        Column now = new Column(feet.x(), feet.z());
        Column before = this.center;
        if (now.equals(before)) {
            return List.of();
        }
        this.center = now;
        List<Column> fresh = new ArrayList<>();
        boolean jump = before == null || horizontalDistSq(before, now) > RADIUS_SQ;
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                if (dx * dx + dz * dz > RADIUS_SQ) {
                    continue;
                }
                Column column = new Column(now.x() + dx, now.z() + dz);
                if (jump || horizontalDistSq(before, column) > RADIUS_SQ) {
                    fresh.add(column);
                }
            }
        }
        return fresh;
    }

    private static long horizontalDistSq(Column a, Column b) {
        long dx = a.x() - b.x();
        long dz = a.z() - b.z();
        return dx * dx + dz * dz;
    }
}
