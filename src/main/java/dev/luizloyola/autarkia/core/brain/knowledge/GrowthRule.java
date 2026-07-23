package dev.luizloyola.autarkia.core.brain.knowledge;

import dev.luizloyola.autarkia.core.brain.sense.Pos;
import java.util.Map;
import java.util.Optional;

/**
 * One kind of thing the sensor recognizes by growing it — the pluggable half of
 * {@link RegionGrowth}. Accepted becomes a {@link PoiMemory}; rejected becomes negative claims,
 * so she does not re-investigate the same non-thing every crossing.
 *
 * <p>Rules are stateless — one instance serves every growth of its kind.
 */
public interface GrowthRule {
    PoiKind kind();

    /** Whether this block belongs to the structure being grown. */
    boolean joins(Pos p, BlockKind kind, BlockProbe probe);

    /**
     * Empty means rejected (logs with no sunlit leaf are a woodpile, not a tree). Probe reads
     * here are not wallet-budgeted — bounded by region size, one-time per growth.
     */
    Optional<Evaluation> evaluate(Map<Pos, BlockKind> blocks, Pos seed, BlockProbe probe);

    /** An accepted structure: where to walk ({@code anchor}) and how much is there ({@code units}). */
    record Evaluation(Pos anchor, int units) {
    }
}
