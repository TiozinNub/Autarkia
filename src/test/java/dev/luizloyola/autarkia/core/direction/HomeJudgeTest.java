package dev.luizloyola.autarkia.core.direction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.terrain.GroundSample;
import dev.luizloyola.anima.core.terrain.Landscape;
import dev.luizloyola.anima.core.terrain.Terrain;
import dev.luizloyola.anima.core.terrain.TerrainRules;
import dev.luizloyola.autarkia.core.direction.HomeJudge.Avoid;
import dev.luizloyola.autarkia.core.direction.HomeJudge.Candidate;
import dev.luizloyola.autarkia.core.direction.HomeJudge.Judgement;
import dev.luizloyola.autarkia.core.direction.HomeJudge.Keep;
import dev.luizloyola.autarkia.core.direction.HomeJudge.KeepColumns;
import dev.luizloyola.autarkia.core.direction.HomeJudge.Refusal;
import dev.luizloyola.autarkia.core.direction.HomeJudge.Known;
import dev.luizloyola.autarkia.core.direction.HomeJudge.Table;
import dev.luizloyola.autarkia.core.direction.HomeJudge.Terms;
import dev.luizloyola.autarkia.core.direction.HomeJudge.Want;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * A place is worth what lasts around it: water beside the plot counts in full and a pond not at
 * all, lava too close refuses the plot, stone counts as the settler knows it, and between two
 * equal plots the nearer wins.
 */
class HomeJudgeTest {

    private static final int SIZE = 161;
    private static final int MID = SIZE / 2;
    private static final int LEVEL = 64;

    /** The shipped table, with a read small enough for a test. */
    private static final Table TABLE = new Table(17, 24, 81, 5,
            new Terms(40, 4, 24, 256), new Terms(30, 8, 56, 8), new Terms(15, 8, 48, 1),
            new Terms(10, 8, 48, 1), 30, 32, 3000, 4);

    private static GroundSample level() {
        GroundSample sample = new GroundSample(0, 0, SIZE, SIZE);
        for (int x = 0; x < SIZE; x++) {
            for (int z = 0; z < SIZE; z++) {
                sample.set(x, z, LEVEL, 0);
            }
        }
        return sample;
    }

    private static void pool(GroundSample sample, int x0, int z0, int x1, int z1, int flags) {
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                sample.set(x, z, LEVEL - 1, flags);
            }
        }
    }

    private static List<Candidate> judge(GroundSample sample, Map<Want, List<Known>> known) {
        return judge(sample, known, Avoid.NONE).ranked();
    }

    private static Judgement judge(GroundSample sample, Map<Want, List<Known>> known, Avoid avoid) {
        Terrain terrain = Terrain.analyse(sample, TABLE.rules(TerrainRules.configured()));
        return HomeJudge.judge(new Landscape(terrain), MID, MID, TABLE, known, avoid);
    }

    private static Optional<Candidate> at(List<Candidate> all, int x, int z) {
        return all.stream().filter(c -> c.x() == x && c.z() == z).findFirst();
    }

    private static double points(Candidate candidate, Want want) {
        return HomeJudge.byWant(candidate).get(want).points();
    }

    @Test
    void waterBesideThePlotCountsInFull() {
        GroundSample sample = level();
        pool(sample, MID + 30, 0, SIZE - 1, SIZE - 1, GroundSample.FLUID); // a lake to the east
        List<Candidate> all = judge(sample, Map.of());

        // A plot centred 12 west of the shore reaches 4 short of it; 25 west, 17 short.
        assertEquals(40, points(at(all, MID + 18, MID).orElseThrow(), Want.WATER), 1e-6);
        assertEquals(40 * 7 / 20.0, points(at(all, MID + 5, MID).orElseThrow(), Want.WATER), 1e-6);
        assertEquals(0, points(at(all, MID - 20, MID).orElseThrow(), Want.WATER), 1e-6);
        // Nearer the shore, the lake takes flat ground from beside the plot.
        assertEquals(MID + 18, all.get(0).x(), "the best plot is the nearest with water in full");
    }

    @Test
    void aPondIsNotALake() {
        GroundSample sample = level();
        pool(sample, MID + 10, MID - 7, MID + 24, MID + 7, GroundSample.FLUID); // 225 columns
        List<Candidate> all = judge(sample, Map.of());

        assertEquals(0, points(at(all, MID - 10, MID).orElseThrow(), Want.WATER), 1e-6);
    }

    @Test
    void lavaTooCloseRefusesThePlot() {
        GroundSample sample = level();
        pool(sample, MID + 20, MID, MID + 20, MID, GroundSample.FLUID | GroundSample.LAVA);
        List<Candidate> all = judge(sample, Map.of());

        // Centred 12 west, the plot's edge is 4 from the lava: refused. 13 west, 5: allowed.
        assertTrue(at(all, MID + 8, MID).isEmpty());
        assertTrue(judge(sample, Map.of(), Avoid.NONE).refused().get(Refusal.LAVA) > 0);
        assertEquals(15, points(at(all, MID + 7, MID).orElseThrow(), Want.LAVA), 1e-6);
        // 30 west, 22 from the edge: 15 · (48 − 22) / 40.
        assertEquals(15 * 26 / 40.0, points(at(all, MID - 10, MID).orElseThrow(), Want.LAVA), 1e-6);
    }

    @Test
    void stoneCountsAsTheSettlerKnowsIt() {
        Map<Want, List<Known>> known = Map.of(Want.STONE, List.of(
                new Known(MID + 40, MID, 20),                  // a stone hill, 40 east
                new Known(MID - 12, MID, 3),                   // three blocks: not a source
                new Known(MID, MID + 40, Known.GLIMPSED)));    // glimpsed, 40 south
        List<Candidate> all = judge(level(), known);

        Candidate here = at(all, MID, MID).orElseThrow();
        // 40 from the centre is 32 from the plot's edge: 30 · (56 − 32) / 48.
        assertEquals(15, points(here, Want.STONE), 1e-6);
        assertEquals(30, points(at(all, MID + 24, MID).orElseThrow(), Want.STONE), 1e-6);
        assertEquals(30, points(at(all, MID, MID + 24).orElseThrow(), Want.STONE), 1e-6,
                "a glimpse counts at full worth");
    }

    @Test
    void betweenEqualPlotsTheNearerWins() {
        List<Candidate> all = judge(level(), Map.of());

        Candidate best = all.get(0);
        assertEquals(MID, best.x());
        assertEquals(MID, best.z());
        assertEquals(LEVEL, best.y());
    }

    @Test
    void roomIsTheFlatGroundBesideThePlot() {
        Candidate best = judge(level(), Map.of()).get(0);

        // 65² − 17² flat columns: 3,936, past the full mark.
        assertEquals(65 * 65 - 17 * 17, HomeJudge.byWant(best).get(Want.ROOM).measure(), 1e-6);
        assertEquals(30, points(best, Want.ROOM), 1e-6);
    }

    @Test
    void apartKeepsPlotsThatShareNoColumn() {
        List<Candidate> apart = HomeJudge.apart(judge(level(), Map.of()), 5);

        assertEquals(5, apart.size());
        for (int i = 0; i < apart.size(); i++) {
            for (int j = i + 1; j < apart.size(); j++) {
                assertFalse(apart.get(i).overlaps(apart.get(j)));
            }
        }
    }

    @Test
    void theBarDropsWithEveryLeg() {
        assertEquals(81, TABLE.bar(0), 1e-9);
        assertEquals(66, TABLE.bar(3), 1e-9);
    }

    @Test
    void theTerrainMustJudgeThePlotsSize() {
        Terrain terrain = Terrain.analyse(level(), TABLE.rules(TerrainRules.configured()));
        Table wider = new Table(21, 24, 81, 5, TABLE.water(), TABLE.stone(), TABLE.lava(),
                TABLE.bee(), 30, 32, 3000, 4);

        assertThrows(IllegalArgumentException.class,
                () -> HomeJudge.judge(new Landscape(terrain), MID, MID, wider, Map.of(), Avoid.NONE));
    }

    @Test
    void anotherPartysHomeKeepsAHomeAway() {
        // Another party's 17-wide plot, its west edge 40 east of here.
        Keep party = new Keep(Refusal.PARTY, MID + 40, MID - 8, MID + 56, MID + 8, 30);
        Judgement judged = judge(level(), Map.of(), new Avoid(List.of(party), List.of()));

        // Centred 2 east, the plot's edge is 30 from theirs: refused. 1 east, 31: allowed.
        assertTrue(at(judged.ranked(), MID + 2, MID).isEmpty());
        assertTrue(at(judged.ranked(), MID + 1, MID).isPresent());
        assertTrue(judged.refused().get(Refusal.PARTY) > 0);
    }

    @Test
    void aVillageKeepsAHomeFromItsBuildings() {
        GroundSample sample = level();
        sample.set(MID + 30, MID, LEVEL + 1, GroundSample.USED); // a house, 30 east
        KeepColumns village = new KeepColumns(Refusal.VILLAGE,
                (x, z) -> x == MID + 30 && z == MID, 10);
        Judgement judged = judge(sample, Map.of(), new Avoid(List.of(), List.of(village)));

        // Centred 18 west of the house, the plot's edge is 10 from it: refused; 19 west, 11: not.
        // Used ground reaches 4 past the house, so the ground itself refuses nearer plots first.
        assertTrue(at(judged.ranked(), MID + 12, MID).isEmpty());
        assertTrue(at(judged.ranked(), MID + 11, MID).isPresent());
        assertEquals(List.of(Refusal.VILLAGE), List.copyOf(judged.refused().keySet()));
    }

    @Test
    void aPlotMayNotTouchARefusedBiome() {
        KeepColumns mushrooms = new KeepColumns(Refusal.BIOME, (x, z) -> z < MID - 10, 0);
        Judgement judged = judge(level(), Map.of(), new Avoid(List.of(), List.of(mushrooms)));

        assertTrue(at(judged.ranked(), MID, MID - 3).isEmpty(), "its north edge in the biome");
        assertTrue(at(judged.ranked(), MID, MID - 2).isPresent(), "its north edge beside it");
    }
}
