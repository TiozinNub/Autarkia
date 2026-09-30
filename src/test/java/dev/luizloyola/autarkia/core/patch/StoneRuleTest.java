package dev.luizloyola.autarkia.core.patch;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.AgentProfile;
import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.core.agent.SpeciesProfile;
import dev.luizloyola.anima.core.agent.TestSpecies;
import dev.luizloyola.anima.core.brain.knowledge.AgentKnowledge;
import dev.luizloyola.anima.core.brain.knowledge.BlockKind;
import dev.luizloyola.anima.core.brain.knowledge.BlockProbe;
import dev.luizloyola.anima.core.brain.knowledge.FakeProbe;
import dev.luizloyola.anima.core.brain.knowledge.GrowthRules;
import dev.luizloyola.anima.core.brain.knowledge.PoiMemory;
import dev.luizloyola.anima.core.brain.knowledge.PoiSensorCore;
import dev.luizloyola.anima.core.brain.knowledge.SenseEvent;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.autarkia.core.tree.Pois;
import dev.luizloyola.autarkia.core.tree.TreeRule;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Exposed stone is remembered by where it is, and a settler standing on a field of it still sees a
 * tree about as soon as on grass: each growth is capped, so the field does not take the whole
 * perception budget.
 */
class StoneRuleTest {

    private static final Pos HERE = new Pos(0, 64, 0);
    private static final double AHEAD = 0.0;
    private static final int FIELD = 40;

    /** The fake's flat world, its ground stone within {@link #FIELD} of the origin. */
    private static final class StoneField implements BlockProbe {
        final FakeProbe world = new FakeProbe();
        final boolean stone;

        StoneField(boolean stone) {
            this.stone = stone;
        }

        @Override
        public int surfaceY(int x, int z) {
            return world.surfaceY(x, z);
        }

        @Override
        public int groundY(int x, int z) {
            return world.groundY(x, z);
        }

        @Override
        public boolean empty(int x, int y, int z) {
            return world.empty(x, y, z);
        }

        @Override
        public int topY(int x, int z) {
            return world.topY(x, z);
        }

        @Override
        public BlockKind at(int x, int y, int z) {
            BlockKind kind = world.at(x, y, z);
            boolean field = Math.abs(x) <= FIELD && Math.abs(z) <= FIELD;
            return stone && field && kind == BlockKind.OTHER && y == FakeProbe.GROUND_Y
                    ? Landmarks.STONE : kind;
        }

        @Override
        public Sight sightAt(int x, int y, int z) {
            return world.sightAt(x, y, z);
        }

        @Override
        public boolean visibleFromEyes(Pos target) {
            return world.visibleFromEyes(target);
        }

        @Override
        public boolean sightClearBetween(Pos from, Pos to) {
            return world.sightClearBetween(from, to);
        }
    }

    @AfterEach
    void forgetWhatGrows() {
        GrowthRules.reset();
    }

    private static void registerRules() {
        GrowthRules.register(BlockKind.LOG, TreeRule.INSTANCE);
        GrowthRules.register(BlockKind.LEAVES, TreeRule.INSTANCE);
        GrowthRules.register(Landmarks.STONE, StoneRule.INSTANCE);
    }

    @Test
    @DisplayName("stone joins only where it is the top of its column")
    void onlyTheTopIsExposed() {
        StoneField probe = new StoneField(true);
        probe.world.set(3, FakeProbe.GROUND_Y + 1, 3, BlockKind.OTHER); // a block on the stone

        assertTrue(StoneRule.INSTANCE.joins(new Pos(2, FakeProbe.GROUND_Y, 2), Landmarks.STONE, probe));
        assertFalse(StoneRule.INSTANCE.joins(new Pos(3, FakeProbe.GROUND_Y, 3), Landmarks.STONE, probe));
        assertFalse(StoneRule.INSTANCE.joins(new Pos(2, FakeProbe.GROUND_Y, 2), BlockKind.OTHER, probe));
    }

    @Test
    @DisplayName("a field of stone is remembered in outcrops of at most the cap")
    void aFieldIsRememberedInCappedOutcrops() {
        registerRules();
        StoneField probe = new StoneField(true);
        AgentKnowledge knowledge = new AgentKnowledge();
        PoiSensorCore sensor = new PoiSensorCore(knowledge, eyed());
        for (int tick = 1; tick <= 200; tick++) {
            sensor.tick(HERE, AHEAD, tick, probe);
        }

        assertFalse(knowledge.all(Landmarks.STONE_POI).isEmpty());
        for (PoiMemory memory : knowledge.all(Landmarks.STONE_POI)) {
            assertTrue(memory.units() <= StoneRule.MAX_BLOCKS, memory.toString());
        }
    }

    @Test
    @DisplayName("standing on stone, a tree is seen about as soon as on grass")
    void stoneDoesNotHideATree() {
        int onGrass = ticksToSeeATree(false);
        int onStone = ticksToSeeATree(true);
        System.out.println("tree seen after " + onGrass + " ticks on grass, " + onStone + " on stone");

        assertTrue(onGrass > 0 && onStone > 0, onGrass + " / " + onStone);
        assertTrue(onStone <= onGrass + 20, "on grass " + onGrass + ", on stone " + onStone);
    }

    private static int ticksToSeeATree(boolean stone) {
        registerRules();
        StoneField probe = new StoneField(stone);
        probe.world.placeOak(10, 6);
        PoiSensorCore sensor = new PoiSensorCore(new AgentKnowledge(), eyed());
        try {
            for (int tick = 1; tick <= 2000; tick++) {
                for (SenseEvent event : sensor.tick(HERE, AHEAD, tick, probe)) {
                    if (event.type() == SenseEvent.Type.NOTED && event.kind() == Pois.TREE) {
                        return tick;
                    }
                }
            }
            return -1;
        } finally {
            GrowthRules.reset();
        }
    }

    private static AgentProfile eyed() {
        Map<ProfileAspect, Double> overrides = Map.of(
                ProfileAspect.PLACES_RADIUS, 24.0,
                ProfileAspect.PLACES_CONE_DEGREES, 360.0,
                ProfileAspect.PLACES_NEAR_RADIUS, 16.0,
                ProfileAspect.PLACES_HORIZON_RADIUS, 0.0,
                ProfileAspect.BODY_HEIGHT, 2.0);
        SpeciesProfile.Builder builder = SpeciesProfile.of("test_stone_eyed");
        for (ProfileAspect aspect : ProfileAspect.all()) {
            builder.set(aspect, overrides.getOrDefault(aspect, TestSpecies.BIPED.get(aspect)));
        }
        return builder.build().fixed();
    }
}
