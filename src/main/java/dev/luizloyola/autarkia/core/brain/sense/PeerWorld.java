package dev.luizloyola.autarkia.core.brain.sense;

import dev.luizloyola.autarkia.core.person.PersonId;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The {@link PeerSensorCore}'s window onto live bodies — the oracle bundle the mod implements,
 * split by cost so the sensor cascades cheapest-first:
 *
 * <ul>
 *   <li>{@link #candidates()} — the cheap radius query, on sweep beats only; the range is already
 *       sneak-shrunk per target ({@code peers.sneak_range_mult}).</li>
 *   <li>{@link #reading(PersonId)} — one fresh observation, only when that peer's cadence is
 *       due.</li>
 *   <li>{@link #inSight(PersonId)} — the expensive one: eye-to-HITBOX rays, cheapest-first with
 *       early-out and any visible part counting, so peeking over a wall works. Called only after
 *       the cone passes, so an occluded person behind their back costs nothing.</li>
 * </ul>
 */
public interface PeerWorld {
    /** Everyone person-shaped inside notice range right now — sweep-beat only. */
    List<PeerReading> candidates();

    /** A fresh observation of this body, or {@code null} when it is gone or out of range. */
    @Nullable PeerReading reading(PersonId id);

    /** Whether any sampled point of this body's hitbox has a clear ray from the observer's eyes. */
    boolean inSight(PersonId id);
}
