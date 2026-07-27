package dev.luizloyola.autarkia.core.brain.knowledge;

import dev.luizloyola.autarkia.core.brain.sense.Pos;
import java.util.List;
import java.util.Map;

/**
 * A finished growth: what a completed scan learned about one connected mass. The sensor turns each
 * {@link Part} into a {@link PoiMemory} plus positive claims over that part's cells, and whatever
 * no part claimed into negative claims; {@link #blocks} is that claim payload and lives only as
 * long as the hand-off, while the durable memory keeps anchor + bounds + units. A mass is however
 * many things the rule found in it ({@link #parts}).
 *
 * @param kind    what the rule was looking for
 * @param partial true when growth stopped at a cap or an unloaded border — there is AT LEAST
 *                this much
 * @param blocks  every joined cell and what it was — the whole mass
 * @param parts   one entry per recognized thing; empty means the rule recognized nothing here
 */
public record GrownRegion(PoiKind kind, boolean partial, Map<Pos, BlockKind> blocks,
                          List<Part> parts) {

    /**
     * One recognized thing inside the mass.
     *
     * @param anchor where to walk to
     * @param bounds the box this part spans (not the mass's)
     * @param units  the rule's size measure — this tree's logs, this body's surface cells
     * @param blocks the cells this part owns, its share of the mass
     */
    public record Part(Pos anchor, Region bounds, int units, Map<Pos, BlockKind> blocks) {
    }

    /** Whether the rule recognized anything at all here. */
    public boolean accepted() {
        return !parts.isEmpty();
    }

    /** One part as a belief, stamped with the current game time. */
    public PoiMemory toMemory(Part part, long now) {
        return new PoiMemory(kind, part.anchor(), part.bounds(), part.units(), partial, now);
    }
}
