package dev.luizloyola.autarkia.core.brain.sense;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The {@link BeingSensorCore}'s window onto live bodies — the oracle bundle the mod implements,
 * split by cost so the sensor cascades cheapest-first: radius, then cone, then line of sight.
 *
 * <ul>
 *   <li>{@link #candidates()} — the cheap radius query, sweep beats only. Already sneak-shrunk per
 *       target ({@code peers.sneak_range_mult}), and always a LivingEntity-wide query, so widening
 *       the sense cost no more.</li>
 *   <li>{@link #reading(BeingId)} — one observation, only when that being's cadence is due.
 *       Persons pay the full classifier, creatures the thin tier-0 read.</li>
 *   <li>{@link #inSight(BeingId)} — the expensive one and the unit the RAY BUDGET meters, called
 *       only after the cone passes. Persons: eye-to-hitbox rays, cheapest-first with early-out.
 *       Creatures and small bodies: One eye-to-center ray.</li>
 * </ul>
 */
public interface BeingWorld {
    /** Everything living inside notice range right now — sweep-beat only. */
    List<BeingReading> candidates();

    /** A fresh observation of this body, or {@code null} when it is gone or out of range. */
    @Nullable BeingReading reading(BeingId id);

    /** Whether this body has a clear ray from the observer's eyes (per-kind ray count). */
    boolean inSight(BeingId id);
}
