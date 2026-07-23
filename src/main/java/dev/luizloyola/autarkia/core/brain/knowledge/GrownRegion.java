package dev.luizloyola.autarkia.core.brain.knowledge;

import dev.luizloyola.autarkia.core.brain.sense.Pos;
import java.util.Map;

/**
 * What one completed scan learned about a connected structure. Accepted becomes a
 * {@link PoiMemory} plus positive claims, rejected becomes negative claims; {@link #blocks}
 * lives only for the hand-off, the durable memory keeping anchor + bounds + units.
 *
 * @param kind     what the rule was looking for
 * @param accepted whether the rule recognized the structure ({@code anchor} is null otherwise)
 * @param anchor   where to walk to, when accepted
 * @param bounds   the box the collection spans
 * @param units    the rule's size measure (grove logs, water surface cells); 0 when rejected
 * @param partial  true when growth stopped at a cap or an unloaded border — there is AT LEAST
 *                 this much
 * @param blocks   every joined cell and what it was — the claim payload
 */
public record GrownRegion(PoiKind kind, boolean accepted, Pos anchor, Region bounds, int units,
                          boolean partial, Map<Pos, BlockKind> blocks) {

    /** The accepted structure as a belief, stamped with the current game time. */
    public PoiMemory toMemory(long now) {
        if (!accepted) {
            throw new IllegalStateException("rejected region has no memory");
        }
        return new PoiMemory(kind, anchor, bounds, units, partial, now);
    }
}
