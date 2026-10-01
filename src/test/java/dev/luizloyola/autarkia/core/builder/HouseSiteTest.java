package dev.luizloyola.autarkia.core.builder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.config.Config;
import dev.luizloyola.anima.core.terrain.GroundSample;
import dev.luizloyola.anima.core.terrain.Terrain;
import dev.luizloyola.anima.core.territory.ChunkKey;
import dev.luizloyola.autarkia.core.bp.Blueprint.Facing;
import dev.luizloyola.autarkia.core.bp.BuildPlan;
import dev.luizloyola.autarkia.core.bp.Footprint;
import dev.luizloyola.autarkia.core.bp.Placement;
import dev.luizloyola.autarkia.core.builder.HouseSite.Choice;
import dev.luizloyola.autarkia.core.builder.HouseSite.Refusal;
import dev.luizloyola.autarkia.core.builder.HouseSite.Shape;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The basic house sited on a meadow HOME of four chunks with its starter chest and workbench: never
 * on water, built ground or beside lava, never touching what is built, never past the area's ring,
 * its door turned to the stores, and no site without a walk to the door.
 */
class HouseSiteTest {

    private static final int LEVEL = 64;
    /** The read: the area, 0..31, and a margin round it wide enough for every pad in the ring. */
    private static final int MIN = -40;
    private static final int SIZE = 112;
    private static final Pos CHEST = new Pos(8, LEVEL + 1, 8);
    private static final Pos BENCH = new Pos(10, LEVEL + 1, 8);
    private static final SortedSet<ChunkKey> AREA = new TreeSet<>(List.of(chunk(0, 0), chunk(1, 0), chunk(0, 1),
            chunk(1, 1)));

    private static List<Shape> shapes;

    @BeforeAll
    static void house() throws IOException {
        BuildPlan plan = BuildOrderTest.plan(BuildOrderTest.bind(BuildOrderTest.read(
                "/data/autarkia/autarkia/blueprint/basic_wooden_house.bp")),
                Map.of("beds", "none", "base", "lv1", "attic", "none"));
        List<Placement> all = new ArrayList<>();
        for (Facing facing : Facing.values()) {
            all.add(new Placement(facing, false));
            all.add(new Placement(facing, true));
        }
        shapes = Shape.of(plan, BuildOrderTest.DICT, all);
    }

    @BeforeEach
    @AfterEach
    void defaults() {
        Config.reset();
    }

    private static ChunkKey chunk(int x, int z) {
        return new ChunkKey(ChunkKey.OVERWORLD, x, z);
    }

    /**
     * A level meadow with something built where the starter base stands, changed by {@code edit} —
     * fixed things, as a neighbour's building would be, until a test unflags them as the mod does.
     */
    private static Terrain meadow(Consumer<GroundSample> edit) {
        GroundSample sample = new GroundSample(MIN, MIN, SIZE, SIZE);
        for (int x = MIN; x < MIN + SIZE; x++) {
            for (int z = MIN; z < MIN + SIZE; z++) {
                sample.set(x, z, LEVEL, 0);
            }
        }
        sample.set(CHEST.x(), CHEST.z(), LEVEL + 1, GroundSample.USED);
        sample.set(BENCH.x(), BENCH.z(), LEVEL + 1, GroundSample.USED);
        edit.accept(sample);
        return Terrain.analyse(sample, HouseSite.groundRules());
    }

    /** The party: the area grows by edge neighbours only, and every walk is its blocks. */
    private static final class Fake implements HouseSite.Party {
        boolean noWay;

        @Override
        public SortedSet<ChunkKey> area() {
            return AREA;
        }

        @Override
        public OptionalInt growth(SortedSet<ChunkKey> footprint) {
            int added = 0;
            for (ChunkKey chunk : footprint) {
                if (AREA.contains(chunk)) {
                    continue;
                }
                if (chunk.edgeNeighbours().stream().noneMatch(AREA::contains)) {
                    return OptionalInt.empty();
                }
                added++;
            }
            return OptionalInt.of(added);
        }

        @Override
        public List<Pos> stores() {
            return List.of(CHEST);
        }

        @Override
        public List<Pos> places() {
            return List.of(CHEST, BENCH);
        }

        @Override
        public OptionalInt route(Pos from, Pos to) {
            return noWay ? OptionalInt.empty() : OptionalInt.of(Math.abs(from.x() - to.x()) + Math.abs(from.z() - to.z()));
        }
    }

    private static HouseSite.Result choose(Terrain ground, Fake party) {
        return HouseSite.choose(ground, shapes, party, HouseSite.Weights.DEFAULTS, 16);
    }

    private static boolean overlaps(Footprint f, int x, int z) {
        return x >= f.minX() && x <= f.maxX() && z >= f.minZ() && z <= f.maxZ();
    }

    @Test
    void everyPlacementOfTheHouseHasAPadRoundItAndADoorstepOutside() {
        assertEquals(8, shapes.size(), "four facings, each as drawn and mirrored");
        for (Shape shape : shapes) {
            Footprint f = shape.footprint();
            Footprint pad = shape.pad();
            assertTrue(pad.minX() <= f.minX() && pad.minZ() <= f.minZ() && pad.maxX() >= f.maxX()
                    && pad.maxZ() >= f.maxZ(), shape + ": the pad holds the footprint");
            assertTrue(overlaps(pad, shape.doorX(), shape.doorZ()), shape + ": and the doorstep");
            assertTrue(overlaps(pad, shape.doorX() + shape.outX(), shape.doorZ() + shape.outZ()),
                    shape + ": and the apron before it");
            Footprint built = shape.built();
            assertTrue(f.minX() <= built.minX() && f.minZ() <= built.minZ() && f.maxX() >= built.maxX()
                    && f.maxZ() >= built.maxZ(), shape + ": what is built is inside the drawing");
            assertFalse(overlaps(built, shape.doorX() + shape.outX(), shape.doorZ() + shape.outZ()),
                    shape + ": the way out leaves what is built");
            assertEquals(1, Math.abs(shape.outX()) + Math.abs(shape.outZ()), shape + ": out is one step");
        }
    }

    @Test
    void aMeadowHasASiteAndItsDoorTurnsToTheStores() {
        HouseSite.Result result = choose(meadow(sample -> { }), new Fake());

        assertFalse(result.best().isEmpty());
        Choice best = result.best().get(0);
        assertEquals(LEVEL, best.y());
        Pos step = best.doorstep();
        double towards = (CHEST.x() - step.x()) * best.shape().outX() + (CHEST.z() - step.z()) * best.shape().outZ();
        assertTrue(towards > 0, "the door turned away from the chest: " + best);
        assertEquals(0, best.terms().get("earthwork"), 1e-9, "a meadow needs no levelling");
    }

    @Test
    void theSitesOfferedAreDistinct() {
        List<Choice> best = choose(meadow(sample -> { }), new Fake()).best();

        assertTrue(best.size() > 1);
        for (int i = 0; i < best.size(); i++) {
            for (int j = i + 1; j < best.size(); j++) {
                Footprint a = best.get(i).pad();
                Footprint b = best.get(j).pad();
                assertFalse(a.minX() <= b.maxX() && b.minX() <= a.maxX() && a.minZ() <= b.maxZ()
                        && b.minZ() <= a.maxZ(), best.get(i) + " and " + best.get(j) + " are one site");
            }
        }
    }

    @Test
    void aHouseNeverTouchesWhatIsBuilt() {
        HouseSite.Result result = choose(meadow(sample -> { }), new Fake());

        assertTrue(result.refused().getOrDefault(Refusal.PADDING, 0) + result.refused().getOrDefault(Refusal.GROUND, 0) > 0);
        for (Choice choice : result.best()) {
            Footprint f = choice.built();
            for (Pos built : List.of(CHEST, BENCH)) {
                assertTrue(built.x() < f.minX() - 1 || built.x() > f.maxX() + 1 || built.z() < f.minZ() - 1
                        || built.z() > f.maxZ() + 1, choice + " within a block of " + built);
                assertFalse(overlaps(choice.pad(), built.x(), built.z()), choice + ": the pad over " + built);
            }
        }
    }

    @Test
    void aCloseNeighbourCostsAndAFarOneDoesNot() {
        for (Choice choice : choose(meadow(sample -> { }), new Fake()).best()) {
            Footprint f = choice.built();
            int gap = Integer.MAX_VALUE;
            for (Pos built : List.of(CHEST, BENCH)) {
                int dx = Math.max(0, Math.max(f.minX() - built.x(), built.x() - f.maxX()));
                int dz = Math.max(0, Math.max(f.minZ() - built.z(), built.z() - f.maxZ()));
                gap = Math.min(gap, Math.max(dx, dz) - 1);
            }
            double expected = HouseSite.Weights.DEFAULTS.padding() * (5 - Math.min(gap, 5)) / 4.0;
            assertEquals(expected, choice.terms().get("padding"), 1e-9, choice + ", gap " + gap);
        }
    }

    @Test
    void aHouseDrawnTightToItsWallsStillKeepsItsGap() {
        // No border and a pad no wider than the walls: only the gap rule keeps it off the chest.
        Footprint walls = new Footprint(-2, -2, 2, 2);
        Shape tight = new Shape(Placement.AS_DRAWN, walls, walls, walls, 0, 1, 2, 0, 1);
        HouseSite.Result result = HouseSite.choose(meadow(sample -> { }), List.of(tight), new Fake(),
                HouseSite.Weights.DEFAULTS, 64);

        assertTrue(result.refused().getOrDefault(Refusal.PADDING, 0) > 0);
        for (Choice choice : result.best()) {
            Footprint f = choice.built();
            for (Pos built : List.of(CHEST, BENCH)) {
                assertTrue(built.x() < f.minX() - 1 || built.x() > f.maxX() + 1 || built.z() < f.minZ() - 1
                        || built.z() > f.maxZ() + 1, choice + " within a block of " + built);
            }
        }
    }

    @Test
    void aSiteMayCoverTheStarterBaseAndPaysToMoveIt() {
        // Water all round a patch too small to miss the base, whose stations the mod unflags: they
        // move out of the way (decision 10).
        Terrain ground = meadow(sample -> {
            for (int x = MIN; x < MIN + SIZE; x++) {
                for (int z = MIN; z < MIN + SIZE; z++) {
                    if (x < -1 || x > 16 || z < -1 || z > 16) {
                        sample.set(x, z, LEVEL - 1, GroundSample.FLUID);
                    }
                }
            }
            sample.unflag(CHEST.x(), CHEST.z(), GroundSample.USED);
            sample.unflag(BENCH.x(), BENCH.z(), GroundSample.USED);
        });
        HouseSite.Result result = choose(ground, new Fake());

        assertFalse(result.best().isEmpty(), "the base is no refusal");
        for (Choice choice : result.best()) {
            int covered = 0;
            for (Pos station : List.of(CHEST, BENCH)) {
                covered += overlaps(choice.pad(), station.x(), station.z()) ? 1 : 0;
            }
            assertTrue(covered > 0, choice + " misses the base, which the patch should not allow");
            assertEquals(covered * HouseSite.Weights.DEFAULTS.baseMoved(), choice.terms().get("base_moved"), 1e-9,
                    choice.toString());
        }
    }

    @Test
    void waterIsNeverUnderThePad() {
        Terrain ground = meadow(sample -> {
            for (int x = 0; x < 32; x++) {
                for (int z = 16; z < 32; z++) {
                    sample.set(x, z, LEVEL - 1, GroundSample.FLUID);
                }
            }
        });
        HouseSite.Result result = choose(ground, new Fake());

        assertFalse(result.best().isEmpty(), "the dry half and the ring still hold a house");
        for (Choice choice : result.best()) {
            Footprint pad = choice.pad();
            assertTrue(pad.maxZ() < 16 || pad.minX() > 31 || pad.maxX() < 0 || pad.minZ() > 31,
                    choice + " stands on the pond");
        }
    }

    @Test
    void lavaKeepsItsDistance() {
        Terrain ground = meadow(sample -> {
            for (int z = MIN; z < MIN + SIZE; z++) {
                sample.set(24, z, LEVEL - 1, GroundSample.FLUID | GroundSample.LAVA);
            }
        });
        HouseSite.Result result = choose(ground, new Fake());

        assertTrue(result.refused().getOrDefault(Refusal.LAVA, 0) > 0);
        for (Choice choice : result.best()) {
            assertTrue(choice.pad().maxX() < 24 - HouseSite.LAVA_GAP || choice.pad().minX() > 24 + HouseSite.LAVA_GAP,
                    choice + " within reach of the lava");
        }
    }

    @Test
    void theAreaGrowsByItsRingAndNoFurther() {
        HouseSite.Result result = choose(meadow(sample -> { }), new Fake());

        for (Choice choice : result.best()) {
            for (ChunkKey chunk : choice.footprint().chunks(ChunkKey.OVERWORLD)) {
                assertTrue(chunk.x() >= -1 && chunk.x() <= 2 && chunk.z() >= -1 && chunk.z() <= 2,
                        choice + " in " + chunk);
            }
        }
    }

    @Test
    void noWalkToTheDoorIsNoSite() {
        Fake party = new Fake();
        party.noWay = true;
        HouseSite.Result result = choose(meadow(sample -> { }), party);

        assertTrue(result.best().isEmpty());
        assertEquals(16, result.refused().get(Refusal.NO_ROUTE));
    }

    @Test
    void aSegmentCrossesARectangleOnlyWhereItPassesOverIt() {
        Footprint f = new Footprint(10, 10, 14, 14);

        assertTrue(HouseSite.crosses(f, new Pos(0, 0, 12), new Pos(20, 0, 12)));
        assertFalse(HouseSite.crosses(f, new Pos(0, 0, 0), new Pos(20, 0, 0)));
        assertFalse(HouseSite.crosses(f, new Pos(0, 0, 12), new Pos(5, 0, 12)), "it stops short");
        assertTrue(HouseSite.crosses(f, new Pos(12, 0, 0), new Pos(12, 0, 20)));
    }
}
