package dev.luizloyola.autarkia.core.brain.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.autarkia.core.brain.sense.Pos;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The leading-crescent geometry: event-driven, zero at rest, full disc on discontinuity. */
class CrescentSamplerTest {

    private static final int R = CrescentSampler.radius();

    @Test
    void firstSightYieldsTheFullDisc() {
        CrescentSampler sampler = new CrescentSampler();
        List<Column> disc = sampler.advance(new Pos(0, 64, 0));

        assertTrue(disc.contains(new Column(0, 0)), "her own column");
        assertTrue(disc.contains(new Column(R, 0)), "rim");
        assertFalse(disc.contains(new Column(R + 1, 0)), "beyond the rim");
        assertTrue(disc.size() > 400 && disc.size() < 470, "≈ πR² = ~452, got " + disc.size());
    }

    @Test
    void standingStillEmitsNothingEvenWhenYChanges() {
        CrescentSampler sampler = new CrescentSampler();
        sampler.advance(new Pos(0, 64, 0));

        assertTrue(sampler.advance(new Pos(0, 64, 0)).isEmpty());
        assertTrue(sampler.advance(new Pos(0, 70, 0)).isEmpty(), "jumping in place is not moving");
    }

    @Test
    void oneStepEmitsOnlyTheLeadingCrescent() {
        CrescentSampler sampler = new CrescentSampler();
        sampler.advance(new Pos(0, 64, 0));
        List<Column> crescent = sampler.advance(new Pos(1, 64, 0));

        assertTrue(crescent.size() >= 2 * R - 5 && crescent.size() <= 2 * R + 5,
                "≈ 2R per block moved, got " + crescent.size());
        assertTrue(crescent.contains(new Column(R + 1, 0)), "the new rim cell dead ahead");
        for (Column c : crescent) {
            long oldDist = (long) c.x() * c.x() + (long) c.z() * c.z();
            assertTrue(oldDist > R * R, c + " was already in range before the step");
        }
    }

    @Test
    void teleportRefillsTheWholeDiscAtTheNewCenter() {
        CrescentSampler sampler = new CrescentSampler();
        sampler.advance(new Pos(0, 64, 0));
        List<Column> disc = sampler.advance(new Pos(1000, 64, -3));

        assertTrue(disc.size() > 400, "a jump beyond R starts a fresh glance");
        assertTrue(disc.contains(new Column(1000, -3)));
        assertFalse(disc.contains(new Column(0, 0)));
    }
}
