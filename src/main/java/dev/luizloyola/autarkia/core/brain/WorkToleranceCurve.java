package dev.luizloyola.autarkia.core.brain;

/**
 * The commitment side's cost budget — deliberately not {@link ToleranceCurve} (decision: Luiz):
 * need-pressure is <em>desperation</em>, unbounded at the starving plateau, while a work item's
 * priority is <em>policy</em> — a job is worth a fixed effort, and nothing on a board ever spends
 * like a starving person.
 */
public final class WorkToleranceCurve {
    /** Blocks of acceptable method cost for the lowest-priority work. Tuning knob. */
    public static final double BASE = 40.0;
    public static final double PER_PRIORITY = 80.0;
    public static final double CAP = 150.0;

    private WorkToleranceCurve() {
    }

    public static double tolerance(double priority) {
        return Math.min(CAP, BASE + PER_PRIORITY * priority);
    }
}
