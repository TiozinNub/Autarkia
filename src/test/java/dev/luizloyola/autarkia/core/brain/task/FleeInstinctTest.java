package dev.luizloyola.autarkia.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import dev.luizloyola.autarkia.core.brain.instinct.FleeInstinct;
import dev.luizloyola.autarkia.core.brain.instinct.Instinct;
import dev.luizloyola.autarkia.core.brain.sense.Pos;
import dev.luizloyola.autarkia.core.brain.sense.Threat;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link FleeInstinct}'s pressure curve — a linear ramp from {@link FleeInstinct#RANGE} down
 * to contact, boosted (and capped at {@code 1.0}) for a threat actively targeting her, and the MAX
 * across every currently-sensed threat — plus its emergency {@link Instinct#failCooldown()}
 * override and the fresh-root contract every instinct shares (see {@link InstinctTest}).
 */
class FleeInstinctTest {

    private final FakeContext ctx = new FakeContext();

    private static Threat threatAt(double distance, boolean targeting) {
        return new Threat(new Pos(0, 64, 0), distance, targeting);
    }

    @Test
    void noThreatsMeansNoPressure() {
        FleeInstinct flee = new FleeInstinct(new Random(0));
        ctx.percepts.threats = List.of();
        assertEquals(0.0, flee.pressure(ctx));
    }

    @Test
    void aThreatAtTheEdgeOfRangeExertsNoPressure() {
        FleeInstinct flee = new FleeInstinct(new Random(0));
        ctx.percepts.threats = List.of(threatAt(FleeInstinct.RANGE, false));
        assertEquals(0.0, flee.pressure(ctx), 1e-9);
    }

    @Test
    void aPassiveThreatCrossesThePreemptLineAtEightPointEightBlocks() {
        FleeInstinct flee = new FleeInstinct(new Random(0));
        ctx.percepts.threats = List.of(threatAt(8.8, false));
        assertEquals(0.6, flee.pressure(ctx), 1e-6);
    }

    @Test
    void contactIsFullPressure() {
        FleeInstinct flee = new FleeInstinct(new Random(0));
        ctx.percepts.threats = List.of(threatAt(4.0, false));
        assertEquals(1.0, flee.pressure(ctx), 1e-9);
    }

    @Test
    void aTargetingThreatCrossesThePreemptLineFurtherOutAtTenPointFiveBlocks() {
        FleeInstinct flee = new FleeInstinct(new Random(0));
        ctx.percepts.threats = List.of(threatAt(10.5, true));
        // (16 - 10.5) / 12 = 0.458333... ; * 1.3 (the targeting bonus) = 0.595833...
        assertEquals(0.5958333333333333, flee.pressure(ctx), 1e-9);
    }

    @Test
    void targetingBonusCapsAtOneEvenAtContact() {
        FleeInstinct flee = new FleeInstinct(new Random(0));
        ctx.percepts.threats = List.of(threatAt(4.0, true)); // uncapped would be 1.0 * 1.3 = 1.3
        assertEquals(1.0, flee.pressure(ctx), 1e-9);
    }

    @Test
    void pressureIsTheMaxAcrossThreatsSoAFarTargetingThreatCanOutweighANearPassiveOne() {
        FleeInstinct flee = new FleeInstinct(new Random(0));
        // Further away but hunting her (0.5 * 1.3 = 0.65) beats closer but merely-nearby (0.5833...).
        ctx.percepts.threats = List.of(threatAt(10.0, true), threatAt(9.0, false));
        assertEquals(0.65, flee.pressure(ctx), 1e-9);
    }

    @Test
    void failCooldownIsTheEmergencyTenTickOverrideNotTheDefaultHundred() {
        FleeInstinct flee = new FleeInstinct(new Random(0));
        assertEquals(10, flee.failCooldown());
        assertEquals(FleeInstinct.FAIL_COOLDOWN, flee.failCooldown());
    }

    @Test
    void rootIsAFreshFleeStepEachGrant() {
        FleeInstinct flee = new FleeInstinct(new Random(0));
        var a = flee.root(ctx);
        var b = flee.root(ctx);
        assertInstanceOf(FleeStep.class, a);
        assertNotSame(a, b, "each grant builds a new tree — never a cached instance");
    }

    @Test
    void describeIsFlee() {
        assertEquals("flee", new FleeInstinct(new Random(0)).describe());
    }
}
