package dev.luizloyola.autarkia.core.brain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Pins {@link ToleranceCurve}'s four plateaus and its boundaries — the mechanism that retired
 * {@code EatFromInventory}'s STARVING band-gate. A pressure exactly on a threshold takes the
 * HIGHER band, matching {@code Needs.band()}.
 */
class ToleranceCurveTest {

    @Test
    void belowPeckishOnlyFreeMethods() {
        assertEquals(0.0, ToleranceCurve.tolerance(0.0));
        assertEquals(0.0, ToleranceCurve.tolerance(0.15)); // wander's idle pressure buys nothing
        assertEquals(0.0, ToleranceCurve.tolerance(0.2999));
    }

    @Test
    void peckishPlateau() {
        assertEquals(15.0, ToleranceCurve.tolerance(0.30), "0.30 is inclusive -> the peckish band");
        assertEquals(15.0, ToleranceCurve.tolerance(0.45));
        assertEquals(15.0, ToleranceCurve.tolerance(0.5999));
    }

    @Test
    void hungryPlateau() {
        assertEquals(60.0, ToleranceCurve.tolerance(0.60), "0.60 is inclusive -> the hungry band");
        assertEquals(60.0, ToleranceCurve.tolerance(0.70));
        assertEquals(60.0, ToleranceCurve.tolerance(0.8499));
    }

    @Test
    void starvingLiftsTheCap() {
        assertTrue(Double.isInfinite(ToleranceCurve.tolerance(0.85)), "0.85 is inclusive -> unbounded");
        assertTrue(Double.isInfinite(ToleranceCurve.tolerance(0.9)));
        assertTrue(Double.isInfinite(ToleranceCurve.tolerance(1.0)));
    }

    /** The named thresholds ARE the hunger bands and the plateau values are the documented ones. */
    @Test
    void constantsMatchTheDocumentedCurve() {
        assertEquals(0.30, ToleranceCurve.PECKISH_PRESSURE);
        assertEquals(0.60, ToleranceCurve.HUNGRY_PRESSURE);
        assertEquals(0.85, ToleranceCurve.STARVING_PRESSURE);
        assertEquals(15.0, ToleranceCurve.PECKISH_TOLERANCE);
        assertEquals(60.0, ToleranceCurve.HUNGRY_TOLERANCE);
    }
}
