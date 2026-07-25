package dev.luizloyola.autarkia.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The immutable value holder: defaults, clamping-with-report, copy-on-write, and the live holder. */
class AutarkiaConfigTest {

    @AfterEach
    void restoreGlobalConfig() {
        // Config is process-wide; leaving a custom one installed would leak into every other test.
        Config.reset();
    }

    @Test
    void defaultsHoldEveryKnobsDeclaredDefault() {
        for (Knob knob : Knob.values()) {
            assertEquals(knob.def(), AutarkiaConfig.DEFAULTS.get(knob), knob.key());
            assertTrue(AutarkiaConfig.DEFAULTS.isDefault(knob), knob.key());
        }
        assertTrue(AutarkiaConfig.DEFAULTS.describeOverrides().isEmpty());
    }

    @Test
    @DisplayName("an empty input means defaults, with nothing to report")
    void emptyInputIsClean() {
        AutarkiaConfig.Loaded loaded = AutarkiaConfig.from(Map.of());
        assertTrue(loaded.clean());
        assertEquals(AutarkiaConfig.DEFAULTS, loaded.config());
    }

    @Test
    @DisplayName("absent knobs keep their default; only what was supplied changes")
    void partialInputLeavesTheRestAlone() {
        AutarkiaConfig.Loaded loaded = AutarkiaConfig.from(Map.of(Knob.SENSE_RADIUS, 20.0));
        assertTrue(loaded.clean());
        assertEquals(20, loaded.config().i(Knob.SENSE_RADIUS));
        assertEquals(Knob.READS_PER_TICK.def(), loaded.config().get(Knob.READS_PER_TICK));
        assertFalse(loaded.config().isDefault(Knob.SENSE_RADIUS));
        assertTrue(loaded.config().isDefault(Knob.READS_PER_TICK));
    }

    @Test
    @DisplayName("an out-of-range value is clamped AND reported — never silently swallowed")
    void outOfRangeIsClampedAndReported() {
        Map<Knob, Double> raw = new EnumMap<>(Knob.class);
        raw.put(Knob.SENSE_RADIUS, 999.0);
        raw.put(Knob.BRAIN_PREEMPT, -1.0);
        AutarkiaConfig.Loaded loaded = AutarkiaConfig.from(raw);

        assertFalse(loaded.clean());
        assertEquals(2, loaded.problems().size(), loaded.problems().toString());
        assertEquals(32, loaded.config().i(Knob.SENSE_RADIUS));
        assertEquals(0.0, loaded.config().d(Knob.BRAIN_PREEMPT));
        assertTrue(loaded.problems().stream().anyMatch(p -> p.startsWith("perception.sense_radius")),
                loaded.problems().toString());
    }

    @Test
    @DisplayName("there is no way to build a config holding an illegal value")
    void withAlwaysClamps() {
        assertEquals(32, AutarkiaConfig.DEFAULTS.with(Knob.SENSE_RADIUS, 10_000.0).i(Knob.SENSE_RADIUS),
                "above the max clamps down to it");
        assertEquals(1, AutarkiaConfig.DEFAULTS.with(Knob.SENSE_RADIUS, -5.0).i(Knob.SENSE_RADIUS),
                "below the min clamps up to it");
        assertEquals(13, AutarkiaConfig.DEFAULTS.with(Knob.SENSE_RADIUS, 12.6).i(Knob.SENSE_RADIUS),
                "a fractional value for a whole-number knob is rounded, not truncated");
        for (Knob knob : Knob.values()) {
            AutarkiaConfig absurd = AutarkiaConfig.DEFAULTS.with(knob, Double.MAX_VALUE);
            assertTrue(knob.accepts(absurd.get(knob)), knob.key() + " survived an absurd value");
        }
    }

    @Test
    void withIsCopyOnWriteAndLeavesTheOriginalAlone() {
        AutarkiaConfig original = AutarkiaConfig.DEFAULTS;
        AutarkiaConfig changed = original.with(Knob.WANDER_RADIUS, 24.0);

        assertEquals(24, changed.i(Knob.WANDER_RADIUS));
        assertEquals((int) Knob.WANDER_RADIUS.def(), original.i(Knob.WANDER_RADIUS),
                "the original must be untouched — readers may be holding it mid-tick");
        assertNotEquals(original, changed);
        assertEquals(original, AutarkiaConfig.DEFAULTS);
    }

    @Test
    void toMapRoundTripsThroughFrom() {
        AutarkiaConfig source = AutarkiaConfig.DEFAULTS
                .with(Knob.SENSE_RADIUS, 20.0)
                .with(Knob.JOURNAL_FILE_SINK, 1.0)
                .with(Knob.BRAIN_STICKINESS, 0.25);
        AutarkiaConfig.Loaded round = AutarkiaConfig.from(source.toMap());

        assertTrue(round.clean());
        assertEquals(source, round.config());
    }

    @Test
    void describeOverridesNamesOnlyWhatDiffers() {
        AutarkiaConfig config = AutarkiaConfig.DEFAULTS.with(Knob.CLAIM_TTL_TICKS, 1200.0);
        List<String> overrides = config.describeOverrides();

        assertEquals(1, overrides.size(), overrides.toString());
        assertTrue(overrides.get(0).startsWith("claims.ttl_ticks = 1200"), overrides.get(0));
        assertTrue(overrides.get(0).contains("default 600"), overrides.get(0));
    }

    @Test
    @DisplayName("the live holder starts at defaults, swaps whole, and resets")
    void holderSwapsAtomically() {
        assertSame(AutarkiaConfig.DEFAULTS, Config.get());

        AutarkiaConfig custom = AutarkiaConfig.DEFAULTS.with(Knob.SENSE_RADIUS, 5.0);
        Config.install(custom);
        assertSame(custom, Config.get());
        assertEquals(5, Config.get().i(Knob.SENSE_RADIUS));

        Config.install(null);
        assertSame(AutarkiaConfig.DEFAULTS, Config.get(), "a null install falls back, never NPEs");

        Config.install(custom);
        Config.reset();
        assertSame(AutarkiaConfig.DEFAULTS, Config.get());
    }
}
