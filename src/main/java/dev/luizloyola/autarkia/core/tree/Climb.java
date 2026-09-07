package dev.luizloyola.autarkia.core.tree;

import dev.luizloyola.anima.core.brain.knowledge.BlockKind;
import dev.luizloyola.anima.core.brain.knowledge.BlockProbe;
import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

/**
 * Where a body has to stand to reach every log of a trunk, and how it gets there (decision: Luiz,
 * 2026-09-07). The question is asked from the trunk's own column: for each log, the lowest feet
 * height in that column from which the arm reaches it with nothing in the way, and the highest
 * of those answers is the height the tree demands.
 *
 * <p>If that height is above where the body stands beside the stump, the trunk is <b>opened</b> —
 * the logs a body's height above the base come out, the base log stays as a floor — and the body
 * <b>steps in</b> onto it. From there the plan goes up a level at a time: break every log the arm
 * reaches, and if any remain, rise one (a carried block placed underfoot) and look again. If the
 * height is not above the body, everything is broken from beside and nobody steps anywhere.
 *
 * <p>The stump itself is never in the plan: it is the floor while the body is in the trunk and
 * comes out last, from the ground.
 *
 * @param stand    where the body works from: the base log's cell one up (in the trunk), or the
 *                 cell beside the stump it already stands in
 * @param needFeetY the feet height the tallest demand works out to
 * @param stepsIn  whether the body works from inside the trunk at all
 * @param stepIn   the logs to break before stepping in — empty when nobody steps in, and empty
 *                 too for a trunk already opened, which still steps in
 * @param levels   the work from each standing height, in order
 * @param complete whether every log above the base is accounted for; false when one is out of
 *                 reach from anywhere the plan can put the body
 */
public record Climb(Pos stand, int needFeetY, boolean stepsIn, List<Pos> stepIn,
                    List<Level> levels, boolean complete) {

    /**
     * The body doing the reaching: its eyes above its feet and how far its arm reaches from
     * them, both read off the live entity through the percepts — the breaker refuses by the same
     * reach, so what this calls reachable the arm agrees with.
     */
    public record Arm(double eyeHeight, double reach) {
        public static Arm of(dev.luizloyola.anima.core.brain.sense.Percepts percepts) {
            return new Arm(percepts.eyeHeight(), percepts.reach());
        }
    }

    /** A backstop on rises: a trunk that still has logs after this many is not a tree to climb. */
    static final int MAX_LEVELS = 64;

    /** From feet at {@code feetY}: {@code breaks} come out, then the body rises one if {@code rise}. */
    public record Level(int feetY, List<Pos> breaks, boolean rise) {
        public Level {
            breaks = List.copyOf(breaks);
        }
    }

    public Climb {
        stepIn = List.copyOf(stepIn);
        levels = List.copyOf(levels);
    }

    /**
     * The logs standing above {@code base} in its own column, base included, read across gaps of
     * up to {@code gap} cells: a trunk a body opened and stepped into is a base, two cells of air,
     * then the rest, and it is still one trunk. Past a wider gap it is somebody else's wood.
     */
    public static List<Pos> column(Pos base, BlockProbe probe, int gap) {
        List<Pos> logs = new ArrayList<>();
        int misses = 0;
        for (int y = base.y(); y < base.y() + MAX_LEVELS && misses <= gap; y++) {
            if (probe.at(base.x(), y, base.z()) == BlockKind.LOG) {
                logs.add(new Pos(base.x(), y, base.z()));
                misses = 0;
            } else {
                misses++;
            }
        }
        return logs;
    }

    /** Whether the arm reaches {@code log}'s centre from eyes over feet at {@code (x, feetY, z)}. */
    public static boolean reaches(Arm arm, int x, int feetY, int z, Pos log) {
        double dx = log.x() - x;
        double dz = log.z() - z;
        double dy = log.y() + 0.5 - (feetY + arm.eyeHeight());
        return dx * dx + dy * dy + dz * dz <= arm.reach() * arm.reach();
    }

    /**
     * The lowest feet height in the column at {@code (x, z)} from which the arm reaches
     * {@code log}, nothing in the way; empty when no height does — the log is too far out.
     */
    public static OptionalInt feetToReach(Arm arm, int x, int z, Pos log) {
        double dx = log.x() - x;
        double dz = log.z() - z;
        double flat = dx * dx + dz * dz;
        double reach = arm.reach() * arm.reach();
        if (flat > reach) {
            return OptionalInt.empty();
        }
        double lowestEyes = log.y() + 0.5 - Math.sqrt(reach - flat);
        return OptionalInt.of((int) Math.ceil(lowestEyes - arm.eyeHeight()));
    }

    /**
     * The plan for {@code logs} (base included) from a body with {@code arm} standing at
     * {@code beside}, needing {@code bodyCells} of clear column.
     */
    public static Climb plan(Arm arm, Pos base, List<Pos> logs, Pos beside, int bodyCells) {
        int need = Integer.MIN_VALUE;
        for (Pos log : logs) {
            if (!log.equals(base)) {
                OptionalInt feet = feetToReach(arm, base.x(), base.z(), log);
                if (feet.isPresent()) {
                    need = Math.max(need, feet.getAsInt());
                }
            }
        }
        if (need <= beside.y()) {
            return fromBeside(arm, logs, beside, need);
        }
        List<Pos> stepIn = new ArrayList<>();
        List<Pos> remaining = new ArrayList<>();
        for (Pos log : sortedByHeight(logs)) {
            if (log.equals(base)) {
                continue;
            }
            boolean inTheWay = log.x() == base.x() && log.z() == base.z()
                    && log.y() > beside.y() && log.y() <= beside.y() + bodyCells;
            (inTheWay ? stepIn : remaining).add(log);
        }
        Pos stand = new Pos(base.x(), beside.y() + 1, base.z());
        List<Level> levels = new ArrayList<>();
        int feet = stand.y();
        while (!remaining.isEmpty() && levels.size() < MAX_LEVELS) {
            List<Pos> breaks = new ArrayList<>();
            for (Pos log : List.copyOf(remaining)) {
                if (reaches(arm, base.x(), feet, base.z(), log)) {
                    breaks.add(log);
                    remaining.remove(log);
                }
            }
            boolean rise = !remaining.isEmpty();
            levels.add(new Level(feet, breaks, rise));
            if (rise) {
                feet++;
            }
        }
        return new Climb(stand, need, true, stepIn, levels, remaining.isEmpty());
    }

    /** Everything the arm reaches from where the body already stands, lowest first. */
    private static Climb fromBeside(Arm arm, List<Pos> logs, Pos beside, int need) {
        List<Pos> breaks = new ArrayList<>();
        for (Pos log : sortedByHeight(logs)) {
            if (reaches(arm, beside.x(), beside.y(), beside.z(), log)) {
                breaks.add(log);
            }
        }
        return new Climb(beside, need, false, List.of(),
                List.of(new Level(beside.y(), breaks, false)), breaks.size() == logs.size());
    }

    private static List<Pos> sortedByHeight(List<Pos> logs) {
        List<Pos> sorted = new ArrayList<>(logs);
        sorted.sort((a, b) -> a.y() != b.y() ? Integer.compare(a.y(), b.y())
                : a.x() != b.x() ? Integer.compare(a.x(), b.x())
                : Integer.compare(a.z(), b.z()));
        return sorted;
    }

    /** How many blocks the body places underfoot on the way up. */
    public int rises() {
        int count = 0;
        for (Level level : levels) {
            if (level.rise()) {
                count++;
            }
        }
        return count;
    }

    /** How many logs the levels break — the step-in logs and the stump not counted. */
    public int toBreak() {
        int count = 0;
        for (Level level : levels) {
            count += level.breaks().size();
        }
        return count;
    }

    /** {@code "open 2 · step in · 9 to break · 5 rises"} or {@code "4 to break from beside"}. */
    public String describe() {
        String tail = complete ? "" : " · some out of reach";
        if (!stepsIn) {
            return toBreak() + " to break from beside" + tail;
        }
        int rises = rises();
        return "open " + stepIn.size() + " · step in · " + toBreak() + " to break · "
                + (rises == 0 ? "no rise" : rises == 1 ? "1 rise" : rises + " rises") + tail;
    }
}
