package dev.luizloyola.autarkia.core.tree;

import dev.luizloyola.anima.core.brain.knowledge.BlockKind;
import dev.luizloyola.anima.core.brain.knowledge.BlockProbe;
import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

/**
 * Where a body has to stand to reach every log of a trunk, how it gets there, and how it gets back
 * down (decisions: Luiz, 2026-09-07 and 2026-09-09). <b>The trunk is counted from the cell the
 * body stands in beside it</b>: whatever sits at that height in the trunk's own column is the step
 * up — the base log on level ground, the second log where the ground is a block higher, the dirt
 * under the base where it is a block lower — and every log above is asked the same question: the
 * lowest feet height in the column from which the arm reaches it with nothing in the way. The
 * highest answer, {@link #needFeetY}, is the height the tree demands, and the body goes that high
 * and no higher.
 *
 * <p>If everything is in reach from beside, it is all broken from there and nobody steps anywhere.
 * Otherwise the trunk is <b>opened</b> — the body's height of cells comes out — and the body gets
 * in: <b>hopping up</b> onto the step up where the side has room for a hop, <b>digging in</b> at
 * its own level where a roof over the head leaves none ({@link Approach.Side#jumpRoom}). From
 * inside it <b>rises</b> to {@code needFeetY} one placed block at a time, breaking only the log in
 * its way over its head, then breaks <b>everything above</b>, then comes <b>down</b> breaking
 * underfoot — the blocks it placed, and after them the step-up log — until its feet are back at
 * floor level, the side's own height: a hop-in has one more block to break on the way down than a
 * dig-in, and dirt stops it, since the axe does not dig. A log left one below floor level is broken
 * <b>from outside</b>, and never the one two below: a body in a hole that deep cannot get out, so
 * that log stays buried.
 *
 * @param stand     where the body works from: on the step up or, dug in, level with it, in the
 *                  trunk's column; else the cell beside the stump it already stands in
 * @param needFeetY the feet height in the column the tallest demand works out to — the top of the
 *                  rises, at or below the stand when there are none
 * @param stepsIn   whether the body works from inside the trunk at all
 * @param digsIn    whether it gets in at its own level, the trunk dug open ahead of it, rather than
 *                  by hopping up onto the step up
 * @param stepIn    what to break before stepping in — the logs, and any leaves, in the body's way;
 *                  empty when nobody steps in, and empty too for a trunk already opened, which
 *                  still steps in
 * @param above     every log over the body's head once it is in, lowest first: the ones in the way
 *                  of a rise come out on the way up, the rest from the top. From beside, every log
 *                  above the floor
 * @param under     the logs broken underfoot on the way back down to floor level — the step-up log
 *                  after a hop-in, nothing after a dig-in
 * @param last      the log one below floor level, broken from outside at the end; never more
 * @param complete  whether every log is accounted for; false when one is out of reach from
 *                  anywhere the plan can put the body, or there is no footing to step in on. A log
 *                  two or more below floor level is left buried on purpose and does not count
 */
public record Climb(Pos stand, int needFeetY, boolean stepsIn, boolean digsIn, List<Pos> stepIn,
                    List<Pos> above, List<Pos> under, List<Pos> last, boolean complete) {

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

    /** A backstop on rises: a trunk that still wants more after this many is not a tree to climb. */
    static final int MAX_RISES = 64;

    public Climb {
        stepIn = List.copyOf(stepIn);
        above = List.copyOf(above);
        under = List.copyOf(under);
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
        for (int y = base.y(); y < base.y() + MAX_RISES && misses <= gap; y++) {
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
     * {@code bodyCells} of clear column, with or without {@code jumpRoom} to hop up from there.
     * {@code probe} is read only for the trunk's own column around the body's height: the step
     * up, and what stands in the way of getting in.
     */
    public static Climb plan(Arm arm, Pos base, List<Pos> logs, BlockProbe probe, Pos beside,
                             boolean jumpRoom, int bodyCells) {
        int floor = beside.y();
        List<Pos> sorted = sortedByHeight(logs);
        int need = Integer.MIN_VALUE;
        boolean fromBeside = true;
        boolean reachable = true;
        for (Pos log : sorted) {
            if (log.y() <= floor) {
                continue;
            }
            OptionalInt feet = feetToReach(arm, base.x(), base.z(), log);
            if (feet.isPresent()) {
                need = Math.max(need, feet.getAsInt());
            } else {
                reachable = false;
            }
            fromBeside &= reaches(arm, beside.x(), floor, beside.z(), log);
        }
        if (fromBeside) {
            return fromBeside(arm, sorted, beside, need);
        }
        // The height the body works from inside: on the step up after a hop, or its own where
        // there is no room to hop. Getting in needs something under that and nothing but wood or
        // leaves in the body's way.
        int feetIn = jumpRoom ? floor + 1 : floor;
        if (!Approach.holds(probe.at(base.x(), feetIn - 1, base.z()))) {
            return fromBeside(arm, sorted, beside, need);
        }
        List<Pos> stepIn = new ArrayList<>();
        for (int y = feetIn; y < feetIn + bodyCells; y++) {
            BlockKind kind = probe.at(base.x(), y, base.z());
            if (kind == BlockKind.LOG || kind == BlockKind.LEAVES) {
                stepIn.add(new Pos(base.x(), y, base.z()));
            } else if (kind != BlockKind.AIR) {
                return fromBeside(arm, sorted, beside, need);
            }
        }
        Pos stand = new Pos(base.x(), feetIn, base.z());
        List<Pos> above = new ArrayList<>();
        List<Pos> under = new ArrayList<>();
        List<Pos> last = new ArrayList<>();
        for (Pos log : sorted) {
            if (log.y() >= feetIn + bodyCells) {
                above.add(log);
            } else if (log.y() >= floor && log.y() < feetIn) {
                under.add(0, log); // highest first: the way down
            } else if (log.y() == floor - 1) {
                last.add(log);
            }
            // Between: opened on the way in. Deeper: buried, and left so.
        }
        int rises = Math.max(0, need - feetIn);
        return new Climb(stand, need, true, !jumpRoom, stepIn, above, under, last,
                reachable && rises <= MAX_RISES);
    }

    /**
     * Everything from where the body already stands: the logs above the floor lowest first, then
     * the one at floor level and the one below it, highest first. Incomplete when a log is out of
     * reach from there — a short tree never is; a tall one lands here only when there was no
     * footing to step in on.
     */
    private static Climb fromBeside(Arm arm, List<Pos> sorted, Pos beside, int need) {
        int floor = beside.y();
        List<Pos> above = new ArrayList<>();
        List<Pos> last = new ArrayList<>();
        boolean complete = true;
        for (Pos log : sorted) {
            if (log.y() > floor) {
                above.add(log);
            } else if (log.y() >= floor - 1) {
                last.add(0, log);
            } else {
                continue; // buried
            }
            complete &= reaches(arm, beside.x(), floor, beside.z(), log);
        }
        return new Climb(beside, need, false, false, List.of(), above, List.of(), last, complete);
    }

    private static List<Pos> sortedByHeight(List<Pos> logs) {
        List<Pos> sorted = new ArrayList<>(logs);
        sorted.sort((a, b) -> a.y() != b.y() ? Integer.compare(a.y(), b.y())
                : a.x() != b.x() ? Integer.compare(a.x(), b.x())
                : Integer.compare(a.z(), b.z()));
        return sorted;
    }

    /**
     * The highest cell the plan knows of — where a later read of the column may stop. With no
     * logs listed (a save from before they were) it is as high as the arm reaches from the top of
     * the rises, which is where the tallest demand put the top.
     */
    public int top() {
        int top = Math.max(stand.y(), needFeetY + 7);
        for (Pos log : above) {
            top = Math.max(top, log.y());
        }
        return top;
    }

    /** How many blocks the body places underfoot on the way up. */
    public int rises() {
        return stepsIn ? Math.max(0, needFeetY - stand.y()) : 0;
    }

    /**
     * Floor level: the height of the side the body came in from, where its feet end up after the
     * way down — one below a hop-in's stand, level with a dig-in's, and the stand itself from
     * beside.
     */
    public int floor() {
        return stepsIn && !digsIn ? stand.y() - 1 : stand.y();
    }

    /**
     * {@code "open 2 · step in · 5 rises · 9 above · 1 underfoot · then 1 from outside"},
     * {@code "open 2 · dig in · 1 rise · 5 above"} or {@code "4 to break from beside"}.
     */
    public String describe() {
        String tail = complete ? ""
                : !stepsIn && needFeetY > stand.y() ? " · no footing to step in"
                : " · some out of reach";
        if (!stepsIn) {
            return (above.size() + last.size()) + " to break from beside" + tail;
        }
        int rises = rises();
        return "open " + stepIn.size() + (digsIn ? " · dig in · " : " · step in · ")
                + (rises == 0 ? "no rise" : rises == 1 ? "1 rise" : rises + " rises")
                + " · " + above.size() + " above"
                + (under.isEmpty() ? "" : " · " + under.size() + " underfoot")
                + (last.isEmpty() ? "" : " · then " + last.size() + " from outside") + tail;
    }
}
