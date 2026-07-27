package dev.luizloyola.autarkia.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import dev.luizloyola.autarkia.core.brain.instinct.FleeInstinct;
import dev.luizloyola.autarkia.core.brain.instinct.Instinct;
import dev.luizloyola.autarkia.core.brain.sense.Being;
import dev.luizloyola.autarkia.core.brain.sense.BeingId;
import dev.luizloyola.autarkia.core.brain.sense.Pos;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link FleeInstinct}'s pressure curve: a linear ramp from reach down to contact,
 * multiplied by the species' danger weight and the visible-gear modifiers, boosted and capped
 * at {@code 1.0} for a threat measurably CLOSING IN, then the MAX across every perceived
 * aggressive being. Non-aggressive and not-yet-made-out beings exert nothing, and a RANGED
 * threat's fear starts farther out.
 */
class FleeInstinctTest {

    private final FakeContext ctx = new FakeContext();

    /** The standard threat: an identified bare-handed zombie — danger weight exactly 1.0. */
    private static Being threatAt(double distance, boolean approaching) {
        return FakePercepts.monsterAt(new Pos(0, 64, 0), distance, approaching);
    }

    private static Being speciesAt(String species, double distance, Being.Gear gear) {
        return new Being(BeingId.of(UUID.randomUUID()), Being.Kind.MONSTER, species, "",
                null, new Pos(0, 64, 0), distance, 1, 0, false, List.of(),
                Being.Activity.IDLE, Being.Locomotion.STILL, false, false, false, false,
                true, gear, Being.Identified.SPECIES, Being.Awareness.SEEN);
    }

    @Test
    void nothingAggressiveMeansNoPressure() {
        FleeInstinct flee = new FleeInstinct(new Random(0));
        ctx.percepts.beings = List.of();
        assertEquals(0.0, flee.pressure(ctx));
    }

    @Test
    void aThreatAtTheEdgeOfRangeExertsNoPressure() {
        FleeInstinct flee = new FleeInstinct(new Random(0));
        ctx.percepts.beings = List.of(threatAt(FleeInstinct.range(), false));
        assertEquals(0.0, flee.pressure(ctx), 1e-9);
    }

    @Test
    void aPassiveThreatCrossesThePreemptLineAtEightPointEightBlocks() {
        FleeInstinct flee = new FleeInstinct(new Random(0));
        ctx.percepts.beings = List.of(threatAt(8.8, false));
        assertEquals(0.6, flee.pressure(ctx), 1e-6);
    }

    @Test
    void contactIsFullPressure() {
        FleeInstinct flee = new FleeInstinct(new Random(0));
        ctx.percepts.beings = List.of(threatAt(4.0, false));
        assertEquals(1.0, flee.pressure(ctx), 1e-9);
    }

    @Test
    void anApproachingThreatCrossesThePreemptLineFurtherOutAtTenPointFiveBlocks() {
        FleeInstinct flee = new FleeInstinct(new Random(0));
        ctx.percepts.beings = List.of(threatAt(10.5, true));
        // (16 - 10.5) / 12 = 0.458333... ; * 1.3 (the approach bonus) = 0.595833...
        assertEquals(0.5958333333333333, flee.pressure(ctx), 1e-9);
    }

    @Test
    void approachBonusCapsAtOneEvenAtContact() {
        FleeInstinct flee = new FleeInstinct(new Random(0));
        ctx.percepts.beings = List.of(threatAt(4.0, true)); // uncapped would be 1.0 * 1.3 = 1.3
        assertEquals(1.0, flee.pressure(ctx), 1e-9);
    }

    @Test
    void pressureIsTheMaxAcrossThreatsSoAFarApproachingThreatCanOutweighANearIdleOne() {
        FleeInstinct flee = new FleeInstinct(new Random(0));
        // Further away but closing in (0.5 * 1.3 = 0.65) beats closer but idle (0.5833...).
        ctx.percepts.beings = List.of(threatAt(10.0, true), threatAt(9.0, false));
        assertEquals(0.65, flee.pressure(ctx), 1e-9);
    }

    @Test
    void aScarierSpeciesMultipliesItsWeightIn() {
        // A creeper (danger 1.6) at the same distance a zombie reads 0.5 → 0.8.
        assertEquals(0.8, FleeInstinct.pressureOf(speciesAt("creeper", 10.0, Being.Gear.NONE)),
                1e-9);
    }

    @Test
    void aRangedSpeciesIsFearedFromFartherOut() {
        // A skeleton (ranged species) at exactly melee reach: reach = 16 * 1.5 = 24, so
        // (24 - 16) / 12 * 1.2 (skeleton weight) = 0.8 — where a zombie there reads zero.
        assertEquals(0.0, FleeInstinct.pressureOf(speciesAt("zombie", 16.0, Being.Gear.NONE)), 1e-9);
        assertEquals(0.8, FleeInstinct.pressureOf(speciesAt("skeleton", 16.0, Being.Gear.NONE)), 1e-9);
    }

    @Test
    void visibleGearMultipliesTheDanger() {
        double bare = FleeInstinct.pressureOf(speciesAt("zombie", 10.0, Being.Gear.NONE));
        double armed = FleeInstinct.pressureOf(speciesAt("zombie", 10.0,
                new Being.Gear(true, false, true, false, false))); // sword + armor
        assertEquals(0.5, bare, 1e-9);
        assertEquals(0.5 * 1.15 * 1.2, armed, 1e-9);
    }

    @Test
    void theUnmadeOutAndTheCalmExertNothing() {
        // aggressive=false covers both a grazing cow and a masked something (the sensor
        // masks aggression below the species tier): neither prices any fear.
        Being calm = new Being(BeingId.of(UUID.randomUUID()), Being.Kind.PASSIVE, "cow", "",
                null, new Pos(0, 64, 0), 2.0, 1, 0, true, List.of(), Being.Activity.IDLE,
                Being.Locomotion.STILL, false, false, false, false, false, Being.Gear.NONE,
                Being.Identified.INDIVIDUAL, Being.Awareness.SEEN);
        assertEquals(0.0, FleeInstinct.pressureOf(calm));
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
