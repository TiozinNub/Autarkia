package dev.luizloyola.autarkia.core.brain.knowledge;

import dev.luizloyola.autarkia.core.brain.sense.Pos;
import dev.luizloyola.autarkia.core.config.Config;
import dev.luizloyola.autarkia.core.config.Knob;
import java.util.ArrayList;
import java.util.List;

/**
 * The "notice as you go" trigger geometry: the columns that <em>newly came into sense range</em> as
 * the person moved — the leading crescent. Nothing is emitted until the feet cell changes, so
 * standing costs zero; one block of movement sweeps ≈ 2R columns, and a discontinuity (first
 * sighting, teleport, any jump beyond R) yields the full disc.
 */
public final class CrescentSampler {
    /** Horizontal sense radius in blocks. Configurable; kept inside the threat-scan scale of 16. */
    public static int radius() {
        return Config.get().i(Knob.SENSE_RADIUS);
    }

    private Column center;

    /**
     * Advances to the person's current feet cell and returns the newly-in-range columns —
     * empty when no cell boundary was crossed since the last call.
     */
    public List<Column> advance(Pos feet) {
        Column now = new Column(feet.x(), feet.z());
        Column before = this.center;
        if (now.equals(before)) {
            return List.of();
        }
        this.center = now;
        // Read the radius once per sweep: one crescent always uses one consistent radius.
        int radius = radius();
        int radiusSq = radius * radius;
        List<Column> fresh = new ArrayList<>();
        boolean jump = before == null || horizontalDistSq(before, now) > radiusSq;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radiusSq) {
                    continue;
                }
                Column column = new Column(now.x() + dx, now.z() + dz);
                if (jump || horizontalDistSq(before, column) > radiusSq) {
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
