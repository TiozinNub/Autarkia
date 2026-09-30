package dev.luizloyola.autarkia.core.direction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.autarkia.core.direction.HomeJudge.Candidate;
import dev.luizloyola.autarkia.core.direction.HomeJudge.Judgement;
import dev.luizloyola.autarkia.core.direction.HomeJudge.Line;
import dev.luizloyola.autarkia.core.direction.HomeJudge.Table;
import dev.luizloyola.autarkia.core.direction.HomeJudge.Terms;
import dev.luizloyola.autarkia.core.direction.HomeJudge.Want;
import dev.luizloyola.autarkia.core.direction.HomeLooking.Look;
import dev.luizloyola.autarkia.core.direction.HomeLooking.Way;
import dev.luizloyola.autarkia.core.direction.HomeSearch.Phase;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

/**
 * The search as a player makes it: settle when a place clears the bar, walk the most promising way
 * when none does, ask less with every leg, go back to the best when patience runs out, and never
 * walk back the way it came.
 */
class HomeSearchTest {

    private static final Table TABLE = new Table(17, 64, 81, 5,
            new Terms(40, 4, 24, 256), new Terms(30, 8, 56, 8), new Terms(15, 8, 48, 1),
            new Terms(10, 8, 48, 1), 30, 32, 3000, 4);
    private static final HomeSearch.Terms WALK = new HomeSearch.Terms(8, 32, 30, 10, 40);
    private static final RandomGenerator NO_NOISE = () -> 0L;
    private static final Pos HERE = new Pos(0, 64, 0);

    private static Candidate plot(int x, int z, double value, double stonePoints) {
        return new Candidate(x, z, 63, 17, value, List.of(
                new Line(Want.WATER, 4, 40), new Line(Want.STONE, 20, stonePoints)));
    }

    /** Eight ways from {@code at}, each a leg's length out, with the given open land. */
    private static List<Way> ways(Pos at, double... open) {
        List<Way> ways = new ArrayList<>();
        for (int k = 0; k < HomeSearch.HEADINGS; k++) {
            double[] d = HomeSearch.direction(k);
            ways.add(new Way(open[k], new Pos(at.x() + (int) Math.round(d[0] * 64), 64,
                    at.z() + (int) Math.round(d[1] * 64)), Set.of()));
        }
        return ways;
    }

    private static Look look(List<Candidate> ranked, List<Way> ways) {
        return new Look(new Judgement(ranked, Map.of()), ways);
    }

    private static final double[] EVEN = {0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5};

    @Test
    void aGoodPlaceIsSettledAtOnce() {
        HomeSearch search = new HomeSearch();
        String line = search.looked(HERE, look(List.of(plot(10, 0, 90, 30)), ways(HERE, EVEN)),
                TABLE, WALK, NO_NOISE);

        assertEquals(Phase.SETTLE, search.phase(), line);
        assertEquals(new Pos(10, 64, 0), search.yard());
    }

    @Test
    void belowTheBarTheScoutWalksTheOpenestWay() {
        HomeSearch search = new HomeSearch();
        search.looked(HERE, look(List.of(plot(10, 0, 70, 30)),
                ways(HERE, 0.1, 0.1, 0.9, 0.1, 0.1, 0.1, 0.1, 0.1)), TABLE, WALK, NO_NOISE);

        assertEquals(Phase.WALK, search.phase());
        assertEquals(new Pos(-64, 64, 0), search.legEnd(), "heading 2 is west");
    }

    @Test
    void everyLegAsksLess() {
        HomeSearch search = new HomeSearch();
        Pos at = HERE;
        search.looked(at, look(List.of(plot(10, 0, 70, 30)), ways(at, EVEN)), TABLE, WALK, NO_NOISE);
        assertEquals(81, search.bar(TABLE), 1e-9);
        for (int leg = 1; leg <= 2; leg++) {
            at = search.legEnd();
            search.arrived();
            search.looked(at, look(List.of(), ways(at, EVEN)), TABLE, WALK, NO_NOISE);
            assertEquals(Phase.WALK, search.phase());
            assertEquals(81 - 5 * leg, search.bar(TABLE), 1e-9);
        }
        at = search.legEnd();
        search.arrived();
        String line = search.looked(at, look(List.of(), ways(at, EVEN)), TABLE, WALK, NO_NOISE);

        assertEquals(66, search.bar(TABLE), 1e-9);
        assertEquals(Phase.SETTLE, search.phase(), "the best seen, worth 70, will do now: " + line);
        assertEquals(new Pos(10, 64, 0), search.yard(), "and it goes back to it");
    }

    @Test
    void patienceRunsOutAfterTheLastLeg() {
        HomeSearch search = new HomeSearch();
        HomeSearch.Terms impatient = new HomeSearch.Terms(2, 32, 30, 10, 40);
        Pos at = HERE;
        search.looked(at, look(List.of(plot(10, 0, 20, 0)), ways(at, EVEN)), TABLE, impatient, NO_NOISE);
        for (int leg = 0; leg < 2; leg++) {
            at = search.legEnd();
            search.arrived();
            search.looked(at, look(List.of(), ways(at, EVEN)), TABLE, impatient, NO_NOISE);
        }

        assertEquals(Phase.SETTLE, search.phase());
    }

    @Test
    void theLostWalkOnUntilTheSearchGivesUp() {
        HomeSearch search = new HomeSearch();
        HomeSearch.Terms brief = new HomeSearch.Terms(8, 3, 30, 10, 40);
        Pos at = HERE;
        for (int leg = 0; leg < 3; leg++) {
            search.looked(at, look(List.of(), ways(at, EVEN)), TABLE, brief, NO_NOISE);
            assertEquals(Phase.WALK, search.phase());
            at = search.legEnd();
            search.arrived();
        }
        search.looked(at, look(List.of(), ways(at, EVEN)), TABLE, brief, NO_NOISE);

        assertEquals(Phase.STRANDED, search.phase());
        assertEquals(new Pos(0, 64, 192), at, "holding the first heading all the way");
    }

    @Test
    void theWayBackIsRuledOut() {
        HomeSearch search = new HomeSearch();
        search.looked(HERE, look(List.of(), ways(HERE, 0.9, 0, 0, 0, 0, 0, 0, 0)), TABLE, WALK, NO_NOISE);
        Pos at = search.legEnd();
        search.arrived();
        // All the open land is behind it now: north, the way it came.
        search.looked(at, look(List.of(), ways(at, 0, 0, 0, 0, 1, 1, 0, 0)), TABLE, WALK, NO_NOISE);

        Pos next = search.legEnd();
        assertTrue(next.z() >= at.z(), "it did not turn back: " + next);
    }

    @Test
    void whatTheBestLacksDrawsTheScout() {
        HomeSearch search = new HomeSearch();
        List<Way> ways = new ArrayList<>(ways(HERE, 0.9, 0, 0, 0, 0, 0, 0, 0));
        Way east = ways.get(6);
        ways.set(6, new Way(0, east.legEnd(), Set.of(Want.STONE)));
        search.looked(HERE, look(List.of(plot(10, 0, 50, 0)), ways), TABLE, WALK, NO_NOISE);

        assertEquals(east.legEnd(), search.legEnd(), "stone to the east, and none at the best plot");
    }

    @Test
    void aStrandedLegBlocksItsHeading() {
        HomeSearch search = new HomeSearch();
        search.looked(HERE, look(List.of(), ways(HERE, 0.9, 0.5, 0, 0, 0, 0, 0, 0)), TABLE, WALK, NO_NOISE);
        Pos south = search.legEnd();

        search.failedLeg(new Pos(0, 64, 10));

        assertEquals(Phase.WALK, search.phase());
        assertTrue(!south.equals(search.legEnd()), "another way: " + search.legEnd());
        assertEquals(0, search.legs(), "a stranded leg is not a leg walked");
    }

    @Test
    void aLegCutShortFarOutIsTheNextStop() {
        HomeSearch search = new HomeSearch();
        search.looked(HERE, look(List.of(), ways(HERE, EVEN)), TABLE, WALK, NO_NOISE);

        search.failedLeg(new Pos(0, 64, 40));

        assertEquals(Phase.LOOK, search.phase());
        assertEquals(1, search.legs());
    }

    @Test
    void aRefusedPlotIsDropped() {
        HomeSearch search = new HomeSearch();
        search.looked(HERE, look(List.of(plot(10, 0, 90, 30)), ways(HERE, EVEN)), TABLE, WALK, NO_NOISE);

        search.claimRefused();

        assertEquals(Phase.LOOK, search.phase());
        assertNull(search.best());
    }

    @Test
    void aRestoredSearchIsTheSameSearch() {
        HomeSearch search = new HomeSearch();
        search.looked(HERE, look(List.of(plot(10, 0, 70, 30)), ways(HERE, EVEN)), TABLE, WALK, NO_NOISE);

        HomeSearch.State saved = search.snapshot();

        assertEquals(saved, HomeSearch.restore(saved).snapshot());
    }
}
