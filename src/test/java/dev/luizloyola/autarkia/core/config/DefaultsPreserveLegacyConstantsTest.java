package dev.luizloyola.autarkia.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.luizloyola.autarkia.core.brain.Arbiter;
import dev.luizloyola.autarkia.core.brain.board.SiteClaims;
import dev.luizloyola.autarkia.core.brain.instinct.DescendInstinct;
import dev.luizloyola.autarkia.core.brain.instinct.FleeInstinct;
import dev.luizloyola.autarkia.core.brain.instinct.WanderInstinct;
import dev.luizloyola.autarkia.core.brain.knowledge.CrescentSampler;
import dev.luizloyola.autarkia.core.brain.knowledge.PoiSensorCore;
import dev.luizloyola.autarkia.core.brain.knowledge.RegionGrowth;
import dev.luizloyola.autarkia.core.log.JournalService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The behaviour-neutrality guard for the config refactor: each literal is transcribed from the
 * {@code static final} constant that used to live at that call site, so an edited default names
 * which behaviour drifted. The brain's numbers came from live observation; an unconfigured server
 * must behave like every build before the config existed.
 */
class DefaultsPreserveLegacyConstantsTest {

    @AfterEach
    void restoreGlobalConfig() {
        Config.reset();
    }

    @Test
    @DisplayName("with no config installed, every tunable reads its pre-config constant")
    void defaultsMatchTheConstantsTheyReplaced() {
        Config.reset();

        assertEquals(0.1, Arbiter.stickiness(), "Arbiter.STICKINESS");
        assertEquals(0.6, Arbiter.preempt(), "Arbiter.PREEMPT");

        assertEquals(16.0, FleeInstinct.range(), "FleeInstinct.RANGE");
        assertEquals(12.0, FleeInstinct.ramp(), "FleeInstinct.RAMP");
        assertEquals(0.45, DescendInstinct.strandedPressure(), "DescendInstinct.PRESSURE");
        assertEquals(0.15, WanderInstinct.idlePressure(), "WanderInstinct.IDLE_PRESSURE");
        assertEquals(8, WanderInstinct.defaultRadius(), "WanderInstinct.DEFAULT_RADIUS");

        assertEquals(12, CrescentSampler.radius(), "CrescentSampler.RADIUS");
        assertEquals(64, PoiSensorCore.readsPerTick(), "PoiSensorCore.READS_PER_TICK");
        assertEquals(512, PoiSensorCore.queueCap(), "PoiSensorCore.QUEUE_CAP");
        assertEquals(512, RegionGrowth.maxBlocks(), "RegionGrowth.MAX_BLOCKS");
        assertEquals(24, RegionGrowth.maxSpread(), "RegionGrowth.MAX_SPREAD");

        assertEquals(600, SiteClaims.ttlTicks(), "SiteClaims.TTL_TICKS");

        assertEquals(256, JournalService.defaultMaxEntriesPerPerson(),
                "JournalService.DEFAULT_MAX_ENTRIES_PER_PERSON");
        assertEquals(20L * 60 * 10, JournalService.defaultMaxAgeTicks(),
                "JournalService.DEFAULT_MAX_AGE_TICKS");
    }

    @Test
    @DisplayName("the relationship the descend instinct's doc depends on still holds")
    void descendStaysBelowThePreemptBar() {
        // Not a restatement of the numbers above but the INVARIANT between two of them: "a chop
        // legitimately mid-climb is never interrupted" holds only while descend sits under preempt.
        Config.reset();
        assertEquals(true, DescendInstinct.strandedPressure() < Arbiter.preempt(),
                "descend_pressure must stay below brain.preempt or a mid-climb chop gets cut");
    }

    @Test
    @DisplayName("a reload retunes live — the whole point of reading through the holder")
    void installedValuesAreVisibleAtTheCallSites() {
        Config.install(AutarkiaConfig.DEFAULTS
                .with(Knob.SENSE_RADIUS, 20.0)
                .with(Knob.BRAIN_PREEMPT, 0.8)
                .with(Knob.CLAIM_TTL_TICKS, 1200.0));

        assertEquals(20, CrescentSampler.radius());
        assertEquals(0.8, Arbiter.preempt());
        assertEquals(1200, SiteClaims.ttlTicks());
    }
}
