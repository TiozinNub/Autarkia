package dev.luizloyola.autarkia.core.tree;

import dev.luizloyola.anima.core.brain.knowledge.BlockKind;
import dev.luizloyola.anima.core.brain.knowledge.BlockProbe;
import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.OptionalInt;

/**
 * Where a body has to stand to reach every log of a trunk, and how it gets there (decision: Luiz,
 * 2026-09-07). <b>The trunk is counted from the cell the body stands in beside it</b> (decision:
 * Luiz, 2026-09-09): whatever sits at that height in the trunk's own column is the step up — the
 * base log on level ground, the second log where the ground is a block higher, the dirt under the
 * base where it is a block lower — and every log above it is asked the same question: the lowest
 * feet height in the column from which the arm reaches it with nothing in the way.
 *
 * <p>If any of those logs is out of the arm's reach from beside, the trunk is <b>opened</b> — the
 * body's height of cells above the step up comes out — and the body <b>steps in</b> onto it. From
 * there the plan goes up a level at a time: break every log the arm reaches, and if any remain,
 * rise one (a carried block placed underfoot) and look again. If everything is in reach from
 * beside, it is all broken from there and nobody steps anywhere.
 *
 * <p>Whatever wood is at or below the step up — the stump, and the base too where the ground is
 * raised — is never in the levels: it is the floor while the body is in the trunk and comes out
 * <b>last</b>, from the ground, highest first. A step up that is ground rather than wood is left
 * alone; it was never part of the tree. Ground more than a block below the base gives the body
 * nothing to step up onto without placing blocks, and the plan says so rather than pretending.
 *
 * @param stand    where the body works from: the cell above the step up, in the trunk's column,
 *                 or the cell beside the stump it already stands in
 * @param needFeetY the feet height in the column the tallest demand works out to
 * @param stepsIn  whether the body works from inside the trunk at all
 * @param stepIn   what to break before stepping in — the logs, and any leaves, in the body's way
 *                 above the step up; empty when nobody steps in, and empty too for a trunk already
 *                 opened, which still steps in
 * @param levels   the work from each standing height, in order
 * @param last     the wood at and below the step up, broken from the ground after everything
 *                 else, highest first
 * @param complete whether every log is accounted for; false when one is out of reach from
 *                 anywhere the plan can put the body, or there is no footing to step in on
 */
public record Climb(Pos stand, int needFeetY, boolean stepsIn, List<Pos> stepIn,
                    List<Level> levels, List<Pos> last, boolean complete) {

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
        last = List.copyOf(last);
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
     * The plan for {@code logs} from a body with {@code arm} standing at {@code beside}, needing
     * {@code bodyCells} of clear column. {@code probe} is read only for the trunk's own column at
     * the body's height and just above it: the step up, and what stands in the way of stepping in.
     */
    public static Climb plan(Arm arm, Pos base, List<Pos> logs, BlockProbe probe, Pos beside,
                             int bodyCells) {
        int floor = beside.y();
        List<Pos> above = new ArrayList<>();
        List<Pos> last = new ArrayList<>();
        for (Pos log : sortedByHeight(logs)) {
            (log.y() > floor ? above : last).add(log);
        }
        Collections.reverse(last); // highest first: the step up, then whatever it stood on
        int need = Integer.MIN_VALUE;
        boolean fromBeside = true;
        for (Pos log : above) {
            OptionalInt feet = feetToReach(arm, base.x(), base.z(), log);
            if (feet.isPresent()) {
                need = Math.max(need, feet.getAsInt());
            }
            fromBeside &= reaches(arm, beside.x(), floor, beside.z(), log);
        }
        if (fromBeside) {
            return fromBeside(arm, above, last, beside, need);
        }
        // Stepping in needs something to stand on, and nothing but wood or leaves in the way.
        if (!Approach.holds(probe.at(base.x(), floor, base.z()))) {
            return fromBeside(arm, above, last, beside, need);
        }
        List<Pos> stepIn = new ArrayList<>();
        for (int y = floor + 1; y <= floor + bodyCells; y++) {
            BlockKind kind = probe.at(base.x(), y, base.z());
            if (kind == BlockKind.LOG || kind == BlockKind.LEAVES) {
                stepIn.add(new Pos(base.x(), y, base.z()));
            } else if (kind != BlockKind.AIR) {
                return fromBeside(arm, above, last, beside, need);
            }
        }
        Pos stand = new Pos(base.x(), floor + 1, base.z());
        List<Pos> remaining = new ArrayList<>(above);
        remaining.removeAll(stepIn);
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
        return new Climb(stand, need, true, stepIn, levels, last, remaining.isEmpty());
    }

    /**
     * Everything the arm reaches from where the body already stands, lowest first, with the low
     * wood still after it. Incomplete when a log is out of reach from there — a short tree never
     * is; a tall one lands here only when there was no footing to step in on.
     */
    private static Climb fromBeside(Arm arm, List<Pos> above, List<Pos> last, Pos beside,
                                    int need) {
        List<Pos> breaks = new ArrayList<>();
        boolean complete = true;
        for (Pos log : above) {
            if (reaches(arm, beside.x(), beside.y(), beside.z(), log)) {
                breaks.add(log);
            } else {
                complete = false;
            }
        }
        for (Pos log : last) {
            complete &= reaches(arm, beside.x(), beside.y(), beside.z(), log);
        }
        return new Climb(beside, need, false, List.of(),
                List.of(new Level(beside.y(), breaks, false)), last, complete);
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

    /** How many logs the levels break — the step-in cells and the last wood not counted. */
    public int toBreak() {
        int count = 0;
        for (Level level : levels) {
            count += level.breaks().size();
        }
        return count;
    }

    /**
     * {@code "open 2 · step in · 9 to break · 5 rises · then 1 from the ground"} or
     * {@code "4 to break from beside"}.
     */
    public String describe() {
        String tail = complete ? ""
                : !stepsIn && needFeetY > stand.y() ? " · no footing to step in"
                : " · some out of reach";
        if (!stepsIn) {
            return (toBreak() + last.size()) + " to break from beside" + tail;
        }
        int rises = rises();
        return "open " + stepIn.size() + " · step in · " + toBreak() + " to break · "
                + (rises == 0 ? "no rise" : rises == 1 ? "1 rise" : rises + " rises")
                + (last.isEmpty() ? "" : " · then " + last.size() + " from the ground") + tail;
    }
}
