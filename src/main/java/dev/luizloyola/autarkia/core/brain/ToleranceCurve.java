package dev.luizloyola.autarkia.core.brain;

/**
 * The pressure → cost-tolerance curve: the maximum acceptable method cost grows with the driving
 * need's pressure ("mildly hungry won't walk 300 blocks for berries; starving will"). The arbiter
 * feeds the ACTIVE instinct's pressure through here and publishes the result as
 * {@link BrainContext#costTolerance()}; the executor refuses any method whose {@code estimateCost}
 * exceeds it. Desperation is PRICED, not hard-gated — {@code EatLastResort} charges for forgone
 * nutrition and this curve decides whether the pressure can afford it.
 *
 * <p><b>Plateaus, not a smooth ramp.</b> The step function keeps the "why did they pay for that?"
 * story a handful of legible bands, and maps 1:1 onto the hunger bands in {@code Needs} — the
 * {@code 0.30 / 0.60 / 0.85} thresholds ARE the PECKISH / HUNGRY / STARVING pressures on the same
 * {@code hunger()} scale. Below PECKISH only FREE methods run; at STARVING the cap lifts entirely.
 * Boundaries are inclusive-below (a pressure exactly on a threshold gets the HIGHER band), matching
 * {@code Needs.band()}.
 */
public final class ToleranceCurve {

    /** PECKISH threshold on the {@code hunger()} pressure scale — where a small cost budget opens. */
    public static final double PECKISH_PRESSURE = 0.30;
    /** HUNGRY threshold — where a real (walk-to-a-chest) budget opens. */
    public static final double HUNGRY_PRESSURE = 0.60;
    /** STARVING threshold — where the cost cap lifts entirely (pay any price). */
    public static final double STARVING_PRESSURE = 0.85;

    /**
     * Budget from PECKISH up: a short errand's worth of walk-blocks. Small enough that raw food
     * (opportunity-priced ≥ 20 per forgone point) and treats (80) stay refused until real hunger.
     */
    public static final double PECKISH_TOLERANCE = 15.0;
    /**
     * Budget from HUNGRY up: enough to justify a modest journey to known food, still below the
     * raw-potato price (80) so they cook-or-forage rather than eating raw while merely hungry.
     */
    public static final double HUNGRY_TOLERANCE = 60.0;

    private ToleranceCurve() {
    }

    /**
     * The maximum method cost (walk-block currency) acceptable at the given need pressure — see the
     * class doc for the plateaus. {@link Double#POSITIVE_INFINITY} means unbounded (the STARVING
     * plateau, and the value the arbiter/driver also uses for manual driving).
     *
     * @param pressure the driving instinct's pressure, {@code 0..1}
     * @return the cost ceiling; every applicable method at or under it is a candidate
     */
    public static double tolerance(double pressure) {
        if (pressure >= STARVING_PRESSURE) {
            return Double.POSITIVE_INFINITY;
        }
        if (pressure >= HUNGRY_PRESSURE) {
            return HUNGRY_TOLERANCE;
        }
        if (pressure >= PECKISH_PRESSURE) {
            return PECKISH_TOLERANCE;
        }
        return 0.0;
    }
}
