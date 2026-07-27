package dev.luizloyola.autarkia.core.brain.knowledge;

import dev.luizloyola.autarkia.core.brain.sense.Pos;
import java.util.List;
import java.util.Map;

/**
 * One kind of thing the sensor recognizes by growing it — the pluggable half of
 * {@link RegionGrowth}. A rule says which blocks belong ({@link #joins}) and judges the finished
 * collection ({@link #evaluate}): accepted things become a {@link PoiMemory}, the rest negative
 * claims, so the same non-thing is not re-investigated every crossing. New detections are new
 * rules; the machinery and the store never change.
 *
 * <p>Rules are stateless — one instance serves every growth of its kind.
 */
public interface GrowthRule {
    PoiKind kind();

    /** Whether this block belongs to the structure being grown. */
    boolean joins(Pos p, BlockKind kind, BlockProbe probe);

    /**
     * Judges the fully-grown collection and <b>individuates</b> it: one evaluation per distinct
     * thing in the mass — a fused canopy is several trees, a lake one body. Growth answers "what is
     * connected"; this answers "how many things is that". An empty list means nothing recognized.
     *
     * <p>Evaluation may read the probe; those reads are bounded by the region's size and
     * not wallet-budgeted — a one-time cost per completed growth.
     */
    List<Evaluation> evaluate(Map<Pos, BlockKind> blocks, Pos seed, BlockProbe probe);

    /**
     * One accepted structure: {@code anchor} to walk to, {@code units} of it, and the
     * {@code blocks} it owns — its share of the mass, claimed under its anchor. Shares are
     * disjoint; cells no evaluation claims are declared not-a-thing.
     */
    record Evaluation(Pos anchor, int units, Map<Pos, BlockKind> blocks) {
    }
}
