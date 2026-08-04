package dev.luizloyola.autarkia.core.patch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.AgentProfile;
import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.core.agent.SpeciesProfile;
import dev.luizloyola.anima.core.agent.TestSpecies;
import dev.luizloyola.anima.core.brain.knowledge.AgentKnowledge;
import dev.luizloyola.anima.core.brain.knowledge.BlockKind;
import dev.luizloyola.anima.core.brain.knowledge.FakeProbe;
import dev.luizloyola.anima.core.brain.knowledge.GrowthRules;
import dev.luizloyola.anima.core.brain.knowledge.PoiMemory;
import dev.luizloyola.anima.core.brain.knowledge.PoiSensorCore;
import dev.luizloyola.anima.core.brain.knowledge.SenseEvent;
import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A scatter is not a mass. The flood can only ever collect a clump, because worldgen leaves grass
 * between the pumpkins of one patch; what turns a spray of clumps into the single belief "there are
 * pumpkins over there" is the STORE's merge radius, not the scan.
 */
class PatchRuleTest {

    private static final Pos HERE = new Pos(0, 64, 0);
    private static final double AHEAD = 0.0;
    /** One above the fake world's flat ground: where a thing standing on the ground stands. */
    private static final int ON_GROUND = FakeProbe.GROUND_Y + 1;

    @BeforeEach
    void registerWhatGrows() {
        GrowthRules.register(PatchRule.PUMPKINS.seed(), PatchRule.PUMPKINS);
        GrowthRules.register(PatchRule.MELONS.seed(), PatchRule.MELONS);
    }

    @AfterEach
    void forgetWhatGrows() {
        GrowthRules.reset();
    }

    @Test
    @DisplayName("a rule joins its own crop and nothing else")
    void eachCropIsItsOwn() {
        FakeProbe probe = new FakeProbe();
        assertTrue(PatchRule.PUMPKINS.joins(HERE, Patches.PUMPKIN, probe));
        assertFalse(PatchRule.PUMPKINS.joins(HERE, Patches.MELON, probe));
        assertFalse(PatchRule.PUMPKINS.joins(HERE, Patches.CACTUS, probe));
        assertFalse(PatchRule.PUMPKINS.joins(HERE, BlockKind.OTHER, probe));
        assertEquals(Patches.PUMPKINS, PatchRule.PUMPKINS.kind());
    }

    @Test
    @DisplayName("a clump is one thing, anchored on the block they were looking at")
    void aClumpIsOneThingAnchoredAtTheSeed() {
        FakeProbe probe = new FakeProbe();
        Pos seed = new Pos(4, ON_GROUND, 4);
        Map<Pos, BlockKind> clump = Map.of(
                seed, Patches.PUMPKIN,
                new Pos(5, ON_GROUND, 4), Patches.PUMPKIN);

        var parts = PatchRule.PUMPKINS.evaluate(clump, seed, probe);

        assertEquals(1, parts.size(), "a clump is not several pumpkins the way a canopy is trees");
        assertEquals(seed, parts.get(0).anchor());
    }

    @Test
    @DisplayName("clumps strewn across one patch collapse into ONE place to go")
    void aStrewnPatchIsOneMemory() {
        // Four clumps inside a worldgen-sized patch, no two touching — which is what
        // PATCH_PUMPKIN actually produces, and what a flood alone would call four discoveries.
        FakeProbe probe = new FakeProbe();
        for (int[] at : new int[][] {{4, 4}, {8, 5}, {5, 9}, {9, 10}}) {
            probe.set(at[0], ON_GROUND, at[1], Patches.PUMPKIN);
        }

        AgentKnowledge knowledge = new AgentKnowledge();
        PoiSensorCore sensor = new PoiSensorCore(knowledge, eyed());
        List<SenseEvent> events = new ArrayList<>();
        for (int tick = 1; tick <= 120; tick++) {
            events.addAll(sensor.tick(HERE, AHEAD, tick, probe));
        }

        // Four separate discoveries — asserted, or this whole test could pass by noticing one
        // pumpkin and never proving the merge did anything at all.
        assertEquals(4, events.stream()
                .filter(e -> e.type() == SenseEvent.Type.NOTED && e.kind() == Patches.PUMPKINS)
                .count(), "each clump is its own growth: the flood cannot cross the grass");

        Collection<PoiMemory> remembered = knowledge.all(Patches.PUMPKINS);
        assertEquals(1, remembered.size(),
                "but the merge radius is what makes them one place: " + remembered);
        PoiMemory patch = remembered.iterator().next();
        assertEquals("", patch.kind().unit(),
                "and it counts nothing, because a merge replaces rather than adds and any "
                        + "number here would be the last clump's size wearing the patch's name");
    }

    @Test
    @DisplayName("two crops side by side stay two beliefs")
    void differentCropsDoNotMerge() {
        FakeProbe probe = new FakeProbe();
        probe.set(4, ON_GROUND, 4, Patches.PUMPKIN);
        probe.set(5, ON_GROUND, 4, Patches.MELON);

        AgentKnowledge knowledge = walk(probe);

        assertEquals(1, knowledge.all(Patches.PUMPKINS).size(), "pumpkins here");
        assertEquals(1, knowledge.all(Patches.MELONS).size(),
                "and melons here too — a wide merge radius must never swallow a DIFFERENT kind "
                        + "of place standing one block away");
    }

    private static AgentKnowledge walk(FakeProbe probe) {
        AgentKnowledge knowledge = new AgentKnowledge();
        PoiSensorCore sensor = new PoiSensorCore(knowledge, eyed());
        for (int tick = 1; tick <= 120; tick++) {
            sensor.tick(HERE, AHEAD, tick, probe);
        }
        return knowledge;
    }

    /** Sees 16 blocks all round, and makes out no skyline — this is a near-field story. */
    private static AgentProfile eyed() {
        Map<ProfileAspect, Double> overrides = Map.of(
                ProfileAspect.PLACES_RADIUS, 16.0,
                ProfileAspect.PLACES_CONE_DEGREES, 360.0,
                ProfileAspect.PLACES_NEAR_RADIUS, 16.0,
                ProfileAspect.PLACES_HORIZON_RADIUS, 0.0,
                ProfileAspect.BODY_HEIGHT, 2.0);
        SpeciesProfile.Builder builder = SpeciesProfile.of("test_patch_eyed");
        for (ProfileAspect aspect : ProfileAspect.values()) {
            builder.set(aspect, overrides.getOrDefault(aspect, TestSpecies.BIPED.get(aspect)));
        }
        return builder.build().fixed();
    }
}
