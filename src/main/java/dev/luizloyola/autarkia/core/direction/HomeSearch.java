package dev.luizloyola.autarkia.core.direction;

import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.autarkia.core.direction.HomeJudge.Candidate;
import dev.luizloyola.autarkia.core.direction.HomeJudge.Line;
import dev.luizloyola.autarkia.core.direction.HomeJudge.Table;
import dev.luizloyola.autarkia.core.direction.HomeJudge.Want;
import dev.luizloyola.autarkia.core.direction.HomeLooking.Look;
import dev.luizloyola.autarkia.core.direction.HomeLooking.Way;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.random.RandomGenerator;
import org.jspecify.annotations.Nullable;

/**
 * A scout's search for a HOME, as a player does it: look around; if this spot is good, settle; if
 * not, pick the way that looks most promising, walk a while and look again, remembering the best
 * place seen. The longer the walk, the less picky; when patience runs out, go back to the best
 * place and settle there (docs/superpowers/specs/2026-09-25-home-search-design.md).
 *
 * <p>Pure: what a stop saw comes in as a {@link Look}, and everything the search holds between
 * stops is its {@link State}, saved whole — a restart changes nothing (decision 15).
 */
public final class HomeSearch {

    /** Headings, 45° apart; heading {@code k} faces yaw {@code 45·k}, 0 being +Z. */
    public static final int HEADINGS = 8;

    /** A leg that fails nearer than this to its stop blocks its heading for that stop. */
    static final int STRANDED_WITHIN = 32;

    /** A leg may not end this near an earlier stop. */
    static final int REVISIT = 48;

    /**
     * Up to this much chance in a heading's score, so two parties from one spot part ways — and
     * enough to beat momentum when the land does not choose: at 5 a scout on a flat world never
     * turned once (2026-09-30). At 15, even ground bends a leg 45° about a third of the time.
     */
    static final double NOISE = 15;

    /**
     * Taken off a heading within 45° of the way it came: more than any forward heading can score,
     * so the way back is walked only when every way on is out (Luiz, 2026-10-01; a scout pinned
     * against a coast stranded at its third stop).
     */
    static final double BACK = 1_000_000;

    /**
     * A settle walk that fails this near the plot's centre counts as arrived. The claim judges the
     * plot, not where the scout stands, so the scout need only be on its ground — and within 6 is
     * well inside the default 17-wide footprint. On the forest (2026-10-02) the centre cell had
     * leaves at head height and the walk stranded 137 times, its bodies 4 blocks off.
     */
    public static final int SETTLE_NEAR = 6;

    /** Settle walks that may fail away from the plot before it is given up as out of reach. */
    public static final int SETTLE_TRIES = 3;

    private static final String[] COMPASS = {"S", "SW", "W", "NW", "N", "NE", "E", "SE"};

    public enum Phase {
        /** Stand and look round, then judge. */
        LOOK,
        /** Walk the leg to {@link #legEnd}. */
        WALK,
        /** Walk to the best plot and claim it. */
        SETTLE,
        /** HOME is claimed. */
        DONE,
        /** Nowhere allowed was found within the search's legs: a later rung's escape (decision 17). */
        STRANDED
    }

    /** How the search walks, from {@code home.*}. */
    public record Terms(int maxLegs, int maxSearch, double land, double keep, double hold) {

        public static Terms configured() {
            return new Terms(HomeKnob.MAX_LEGS.i(), HomeKnob.MAX_SEARCH.i(), HomeKnob.HEADING_LAND.d(),
                    HomeKnob.HEADING_KEEP.d(), HomeKnob.HEADING_HOLD.d());
        }
    }

    /** One heading from the current stop: its score, and where its leg ends. */
    public record Option(int heading, double score, @Nullable Pos legEnd) {
    }

    /**
     * Everything the search holds.
     *
     * @param firstAt the legs walked when the first plot was found, -1 before; patience starts there
     * @param heading the last leg's heading, -1 before the first
     * @param blocked headings whose leg stranded short from the current stop
     * @param options the current stop's headings, scored, for another pick after a stranded leg
     * @param settleFails the walks to the best plot that failed away from it
     * @param unreached plots given up as out of reach, never taken again
     */
    public record State(@Nullable Pos start, List<Pos> stops, int legs, int firstAt, int heading,
                        List<Integer> blocked, @Nullable Candidate best, @Nullable Pos legEnd,
                        Phase phase, List<Option> options, int settleFails, List<Candidate> unreached) {

        public State {
            stops = List.copyOf(stops);
            blocked = List.copyOf(blocked);
            options = List.copyOf(options);
            unreached = List.copyOf(unreached);
        }
    }

    private @Nullable Pos start;
    private final List<Pos> stops = new ArrayList<>();
    private int legs;
    private int firstAt = -1;
    private int heading = -1;
    private final Set<Integer> blocked = new LinkedHashSet<>();
    private @Nullable Candidate best;
    private @Nullable Pos legEnd;
    private Phase phase = Phase.LOOK;
    private final List<Option> options = new ArrayList<>();
    private int settleFails;
    private final List<Candidate> unreached = new ArrayList<>();

    public static HomeSearch restore(State state) {
        HomeSearch search = new HomeSearch();
        search.start = state.start();
        search.stops.addAll(state.stops());
        search.legs = state.legs();
        search.firstAt = state.firstAt();
        search.heading = state.heading();
        search.blocked.addAll(state.blocked());
        search.best = state.best();
        search.legEnd = state.legEnd();
        search.phase = state.phase();
        search.options.addAll(state.options());
        search.settleFails = state.settleFails();
        search.unreached.addAll(state.unreached());
        return search;
    }

    public State snapshot() {
        return new State(start, stops, legs, firstAt, heading, List.copyOf(blocked), best, legEnd,
                phase, options, settleFails, unreached);
    }

    public Phase phase() {
        return phase;
    }

    public @Nullable Pos legEnd() {
        return legEnd;
    }

    public @Nullable Candidate best() {
        return best;
    }

    public int legs() {
        return legs;
    }

    /** Where the scout last looked round, or null before the first look. */
    public @Nullable Pos lastStop() {
        return stops.isEmpty() ? null : stops.get(stops.size() - 1);
    }

    /** Where the scout stands to claim the best plot: its centre, on the ground. */
    public @Nullable Pos settleAt() {
        return best == null ? null : new Pos(best.x(), best.y() + 1, best.z());
    }

    /** What a place must be worth now; infinite before the first plot, since patience starts there. */
    public double bar(Table table) {
        return firstAt < 0 ? Double.POSITIVE_INFINITY : table.bar(legs - firstAt);
    }

    /** The unit step of heading {@code k}: {@code {dx, dz}}. */
    public static double[] direction(int k) {
        double radians = Math.toRadians(45.0 * k);
        return new double[] {-Math.sin(radians), Math.cos(radians)};
    }

    public static String compass(int k) {
        return k < 0 ? "-" : COMPASS[Math.floorMod(k, HEADINGS)];
    }

    /**
     * What one stop at {@code at} saw: keep the best plot, settle if it clears the bar or patience
     * is out, otherwise pick a heading. Returns the stop's journal line.
     */
    public String looked(Pos at, Look look, Table table, Terms terms, RandomGenerator random) {
        if (start == null) {
            start = at;
        }
        stops.add(at);
        blocked.clear();
        options.clear();
        List<Candidate> ranked = look.judgement().ranked();
        Candidate top = null;
        for (Candidate plot : ranked) {
            if (unreached.stream().noneMatch(plot::overlaps)) {
                top = plot;
                break;
            }
        }
        // A tie goes to the plot here, not the one legs back: on even ground every stop scores
        // alike, and a strict win walked a scout 450 blocks back to its first stop (2026-09-30).
        if (top != null && (best == null || top.value() >= best.value())) {
            if (firstAt < 0) {
                firstAt = legs;
            }
            best = top;
        }
        double bar = bar(table);
        String seen = "looked around at " + at(at) + ": "
                + (top == null ? "no plot allowed" : "best " + plot(top) + " worth " + worth(top.value()))
                + (best == null ? "" : "; best so far " + plot(best) + " worth " + worth(best.value())
                        + ", bar " + worth(bar));
        if (best != null && best.value() >= bar) {
            settle();
            return seen + "; settling there";
        }
        if (best != null && legs - firstAt >= terms.maxLegs()) {
            settle();
            return seen + "; going back to the best";
        }
        if (best == null && legs >= terms.maxSearch()) {
            strand();
            return seen + "; nowhere found in " + legs + " legs";
        }
        for (int k = 0; k < HEADINGS; k++) {
            options.add(new Option(k, score(k, at, look.ways().get(k), table, terms, random),
                    look.ways().get(k).legEnd()));
        }
        return seen + "; " + pick();
    }

    /** The leg's end is reached: the next stop. */
    public void arrived() {
        legs++;
        legEnd = null;
        phase = Phase.LOOK;
    }

    /**
     * The leg could not be walked. Stranded near the stop, that heading is blocked there and another
     * is picked; farther out, where the scout stands is the next stop. Returns a journal line.
     */
    public String failedLeg(Pos at) {
        Pos stop = stops.isEmpty() ? at : stops.get(stops.size() - 1);
        if (distance(at, stop) >= STRANDED_WITHIN) {
            arrived();
            return "the leg " + compass(heading) + " ended short at " + at(at) + "; looking from there";
        }
        blocked.add(heading);
        return "the leg " + compass(heading) + " is blocked; " + pick();
    }

    /** Whether {@code at} is near enough the best plot's centre to claim it from. */
    public boolean nearPlot(Pos at) {
        Pos plot = settleAt();
        return plot != null && distance(at, plot) <= SETTLE_NEAR;
    }

    /**
     * The walk to the best plot failed away from it. After {@link #SETTLE_TRIES} the plot is out of
     * reach: it is dropped as a refused one is, and no plot over it is taken again — else the next
     * look, from beside it, picks it once more. Returns the journal line when it is given up.
     */
    public @Nullable String failedSettle(Pos at) {
        if (best == null || ++settleFails < SETTLE_TRIES) {
            return null;
        }
        String line = "could not reach the plot at " + plot(best) + " in " + SETTLE_TRIES
                + " tries; looking again from " + at(at);
        unreached.add(best);
        claimRefused();
        return line;
    }

    /** The plot was refused when the scout stood on it: drop it and judge again from there. */
    public void claimRefused() {
        settleFails = 0;
        best = null;
        legEnd = null;
        phase = Phase.LOOK;
    }

    public void claimed() {
        phase = Phase.DONE;
    }

    private String pick() {
        Option chosen = null;
        for (Option option : options) {
            if (option.score() == Double.NEGATIVE_INFINITY || blocked.contains(option.heading())) {
                continue;
            }
            if (chosen == null || option.score() > chosen.score()) {
                chosen = option;
            }
        }
        if (chosen == null) {
            if (best != null) {
                settle();
                return "every way ruled out; going back to the best";
            }
            strand();
            return "every way ruled out, and nowhere found";
        }
        heading = chosen.heading();
        legEnd = chosen.legEnd();
        phase = Phase.WALK;
        // Only BACK takes a score below zero.
        return (chosen.score() < 0 ? "every way on ruled out; heading back " : "heading ")
                + compass(heading) + " to " + at(legEnd);
    }

    /**
     * A heading's worth: open land ahead, the wants the best plot lacks that lie that way, and
     * momentum, less {@link #BACK} for the way it came — or ruled out, as a leg ending by an earlier
     * stop, no leg at all, or the way back once a plot has been found.
     */
    private double score(int k, Pos at, Way way, Table table, Terms terms, RandomGenerator random) {
        if (way.legEnd() == null) {
            return Double.NEGATIVE_INFINITY;
        }
        int off = heading < 0 ? -1 : Math.min(Math.floorMod(k - heading, HEADINGS),
                Math.floorMod(heading - k, HEADINGS));
        boolean back = off >= 3;
        if (back && best != null) {
            return Double.NEGATIVE_INFINITY; // with a plot found, going back to it beats walking back
        }
        for (int i = 0; i < stops.size() - 1; i++) {
            if (distance(way.legEnd(), stops.get(i)) < REVISIT) {
                return Double.NEGATIVE_INFINITY;
            }
        }
        double score = terms.land() * way.openLand();
        for (Want want : List.of(Want.WATER, Want.STONE, Want.LAVA, Want.BEE)) {
            double worth = worth(table, want);
            if (worth > 0 && missing(want, worth) && way.ahead().contains(want)) {
                score += worth;
            }
        }
        if (off == 0) {
            score += best == null ? terms.hold() : terms.keep();
        } else if (off == 1) {
            score += terms.keep() / 2;
        }
        return score + random.nextDouble() * NOISE - (back ? BACK : 0);
    }

    /** Whether the best plot so far scores under half a want's worth on it. */
    private boolean missing(Want want, double worth) {
        if (best == null) {
            return true;
        }
        for (Line line : best.lines()) {
            if (line.want() == want) {
                return line.points() < worth / 2;
            }
        }
        return true;
    }

    private static double worth(Table table, Want want) {
        return switch (want) {
            case WATER -> table.water().worth();
            case STONE -> table.stone().worth();
            case LAVA -> table.lava().worth();
            case BEE -> table.bee().worth();
            case ROOM -> table.roomWorth();
        };
    }

    private void settle() {
        settleFails = 0;
        legEnd = null;
        phase = Phase.SETTLE;
    }

    private void strand() {
        legEnd = null;
        phase = Phase.STRANDED;
    }

    private static double distance(Pos a, Pos b) {
        return Math.hypot(a.x() - b.x(), a.z() - b.z());
    }

    private static String at(@Nullable Pos p) {
        return p == null ? "-" : "(" + p.x() + ", " + p.y() + ", " + p.z() + ")";
    }

    private static String plot(Candidate plot) {
        return "(" + plot.x() + ", " + plot.y() + ", " + plot.z() + ")";
    }

    private static String worth(double value) {
        return Double.isInfinite(value) ? "-" : String.valueOf(Math.round(value));
    }
}
