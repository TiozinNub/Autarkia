package dev.luizloyola.autarkia.core.tree;

import dev.luizloyola.anima.core.brain.act.Leaner;
import dev.luizloyola.anima.core.brain.knowledge.BlockKind;
import dev.luizloyola.anima.core.brain.knowledge.BlockProbe;
import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import org.jspecify.annotations.Nullable;

/**
 * Where a body has to stand to reach every log of a trunk, how it gets there, and how it gets back
 * down (decisions: Luiz, 2026-09-07 to 2026-09-09). <b>The trunk is counted from the cell the
 * body stands in beside it</b>: whatever sits at that height in the trunk's own column is the step
 * up — the base log on level ground, the second log where the ground is a block higher, the dirt
 * under the base where it is a block lower — and every log above is asked the same question: the
 * lowest feet height from which the arm reaches it with nothing in the way. The highest answer,
 * {@link #needFeetY}, is the height the tree demands, and the body goes that high and no higher.
 *
 * <p>If everything is in reach from beside, it is all broken from there and nobody steps anywhere.
 * Otherwise the trunk is <b>opened</b> — the body's height of cells comes out — and the body gets
 * in: <b>hopping up</b> onto the step up where the side has room for a hop, <b>digging in</b> at
 * its own level where a roof over the head leaves none ({@link Approach.Side#jumpRoom}).
 *
 * <p><b>A lone trunk</b> is climbed: from inside the body <b>rises</b> to {@code needFeetY} one
 * placed block at a time, breaking only the log in its way over its head, then breaks
 * <b>everything above</b>, then comes <b>down</b> breaking underfoot — the blocks it placed, and
 * after them the step-up log — until its feet are back at floor level, the side's own height.
 *
 * <p><b>A 2×2 giant</b> is spiralled (decision: Luiz, 2026-09-09): three cells are opened on the
 * way in, the body's two and one for a hop, and from then on the next column round — clockwise or
 * not, drawn once — has three cells opened one higher, and the body hops into that slot. Nothing
 * is ever placed: each column keeps a log every fourth level, and those are the stairs. The
 * spiral goes as far as they do — a column with no log at the height the body would hop from
 * ends it — and every log is priced from the lowest of its stands that reaches it, never from a
 * column the body will not be in. From the top everything above comes out; then the body steps
 * back down the spiral, breaking each stair as it leaves it, to the entry stand, where it takes
 * whatever the other columns still hold, highest first, and then goes down as a lone trunk does.
 *
 * <p><b>Branches</b> — the tree's logs off its columns — are taken on the way down (decision:
 * Luiz, 2026-09-10): from the top once the trunk above is clear, then from every level the body
 * comes down to, and from outside at the end, each time every branch the arm reaches from where
 * it stands. One past the arm from every stand may still be in reach of a body <b>leaning</b> —
 * crouched at the edge of the stand whose crouched eyes are nearest its height (decision: Luiz,
 * 2026-09-10) — and is taken from there, {@link #leans}. What no stand reaches either way stays,
 * and is counted.
 *
 * <p>Either way, a log left one below floor level is broken <b>from outside</b>, and never the one
 * two below: a body in a hole that deep cannot get out, so that log stays buried.
 *
 * @param stand     where the body works from: on the step up or, dug in, level with it, in the
 *                  entry column; else the cell beside the stump it already stands in
 * @param needFeetY the feet height the tallest demand works out to — the top of the rises or the
 *                  spiral, at or below the stand when there are none
 * @param stepsIn   whether the body works from inside the trunk at all
 * @param digsIn    whether it gets in at its own level, the trunk dug open ahead of it, rather than
 *                  by hopping up onto the step up
 * @param stepIn    what to break before stepping in — the logs, and any leaves, in the body's way;
 *                  empty when nobody steps in, and empty too for a trunk already opened, which
 *                  still steps in
 * @param above     every log the body takes from inside, lowest first: for a lone trunk the ones
 *                  over its head, on the way up and from the top; for a giant everything that is
 *                  not the way in, the way down or from outside. From beside, every log above the
 *                  floor
 * @param under     the logs broken underfoot on the way back down to floor level — the step-up log
 *                  after a hop-in, nothing after a dig-in
 * @param last      the logs one below floor level, broken from outside at the end; one per column,
 *                  never more
 * @param complete  whether every log is accounted for; false when one is out of reach from
 *                  anywhere the plan can put the body, or there is no footing to step in on. A log
 *                  two or more below floor level is left buried on purpose and does not count
 * @param columns   the trunk's columns at base height, clockwise from above — one for a lone
 *                  trunk, four for a giant; the entry column is the stand's
 * @param clockwise which way round a giant is spiralled
 * @param branches  the tree's logs off its columns, as detection assigned them; taken from
 *                  wherever the body reaches them on the way down
 * @param leans     the branches only a lean reaches, each with the stand it is leant for from
 */
public record Climb(Pos stand, int needFeetY, boolean stepsIn, boolean digsIn, List<Pos> stepIn,
                    List<Pos> above, List<Pos> under, List<Pos> last, boolean complete,
                    List<Pos> columns, boolean clockwise, List<Pos> branches, List<Lean> leans) {

    /**
     * The body doing the reaching: its eyes above its feet, standing and crouched, and how far
     * its arm reaches from them, all read off the live entity through the percepts — the breaker
     * refuses by the same reach, so what this calls reachable the arm agrees with.
     */
    public record Arm(double eyeHeight, double crouchedEyeHeight, double reach) {
        public static Arm of(dev.luizloyola.anima.core.brain.sense.Percepts percepts) {
            return new Arm(percepts.eyeHeight(), percepts.crouchedEyeHeight(), percepts.reach());
        }
    }

    /** A branch taken by leaning: the branch, and the stand the body leans from. */
    public record Lean(Pos branch, Pos stand) {
    }

    /** A backstop on rises: a trunk that still wants more after this many is not a tree to climb. */
    static final int MAX_RISES = 64;

    /**
     * How much of the arm's length the plan does not count on. It measures from the centre of a
     * cell, and a body never stands exactly there: an acacia limb 4.48 from the centre of the
     * cell beside its stump was refused by an arm of 4.5 (2026-09-10). What is inside by less
     * than this is planned as out of reach, and taken from a nearer cell or not at all.
     */
    public static final double SLACK = 0.3;

    /** Cells a giant's slot has: the body's two, and one more so it can hop on from there. */
    public static final int SLOT = 3;

    /**
     * What a lean keeps of the arm. Less than {@link #SLACK}: a leaning body is put at a known
     * point on purpose, so only the creep's own tolerance is unknown.
     */
    public static final double LEAN_SLACK = 0.1;

    public Climb {
        stepIn = List.copyOf(stepIn);
        above = List.copyOf(above);
        under = List.copyOf(under);
        last = List.copyOf(last);
        columns = columns.isEmpty() ? List.of(stand) : List.copyOf(columns);
        branches = List.copyOf(branches);
        leans = List.copyOf(leans);
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

    /**
     * Whether the arm reaches {@code log}'s centre from eyes over feet at {@code (x, feetY, z)},
     * with {@link #SLACK} to spare — what the plan counts on.
     */
    public static boolean reaches(Arm arm, int x, int feetY, int z, Pos log) {
        return reaches(arm, x, feetY, z, log, SLACK);
    }

    /** The same with {@code slack} to spare; none is the arm's whole length, what a try costs nothing to ask. */
    public static boolean reaches(Arm arm, int x, int feetY, int z, Pos log, double slack) {
        double dx = log.x() - x;
        double dz = log.z() - z;
        double dy = log.y() + 0.5 - (feetY + arm.eyeHeight());
        double reach = arm.reach() - slack;
        return dx * dx + dy * dy + dz * dz <= reach * reach;
    }

    /**
     * How far {@code log}'s centre is from the eyes of a body crouched at the edge of the cell at
     * {@code (x, feetY, z)}, leant {@link Leaner#MAX_LEAN} toward it.
     */
    public static double leaningDistance(Arm arm, int x, int feetY, int z, Pos log) {
        double dx = log.x() - x;
        double dz = log.z() - z;
        double flat = Math.max(0.0, Math.sqrt(dx * dx + dz * dz) - Leaner.MAX_LEAN);
        double dy = log.y() + 0.5 - (feetY + arm.crouchedEyeHeight());
        return Math.sqrt(flat * flat + dy * dy);
    }

    /**
     * Whether a lean from the cell at {@code (x, feetY, z)} reaches {@code log}, with
     * {@link #LEAN_SLACK} to spare. Never for a log over the stand: there is no lean to make.
     */
    public static boolean reachesLeaning(Arm arm, int x, int feetY, int z, Pos log) {
        double dx = log.x() - x;
        double dz = log.z() - z;
        return dx * dx + dz * dz > Leaner.MAX_LEAN * Leaner.MAX_LEAN
                && leaningDistance(arm, x, feetY, z, log) <= arm.reach() - LEAN_SLACK;
    }

    /**
     * The lowest feet height in the column at {@code (x, z)} from which the arm reaches
     * {@code log}, nothing in the way; empty when no height does — the log is too far out.
     */
    public static OptionalInt feetToReach(Arm arm, int x, int z, Pos log) {
        double dx = log.x() - x;
        double dz = log.z() - z;
        double flat = dx * dx + dz * dz;
        double reach = (arm.reach() - SLACK) * (arm.reach() - SLACK);
        if (flat > reach) {
            return OptionalInt.empty();
        }
        double lowestEyes = log.y() + 0.5 - Math.sqrt(reach - flat);
        return OptionalInt.of((int) Math.ceil(lowestEyes - arm.eyeHeight()));
    }

    /** The plan for a lone, branchless trunk at {@code base}; the full shape is the other overload. */
    public static Climb plan(Arm arm, Pos base, List<Pos> logs, BlockProbe probe, Pos beside,
                             boolean jumpRoom, int bodyCells) {
        return plan(arm, List.of(base), base, logs, List.of(), probe, beside, jumpRoom, false,
                bodyCells);
    }

    /**
     * The plan for {@code logs}, standing in {@code columns} (one, or a giant's four), from a body
     * with {@code arm} standing at {@code beside}, needing {@code bodyCells} of clear column, with
     * or without {@code jumpRoom} to hop up from there. {@code entry} is the column beside the
     * side taken, {@code clockwise} the way round a giant, and {@code branches} the tree's logs
     * off its columns. {@code probe} is read only for the entry column around the body's height:
     * the step up, and what stands in the way of getting in.
     */
    public static Climb plan(Arm arm, List<Pos> columns, Pos entry, List<Pos> logs,
                             List<Pos> branches, BlockProbe probe, Pos beside, boolean jumpRoom,
                             boolean clockwise, int bodyCells) {
        List<Pos> ring = ringOf(columns);
        boolean giant = ring.size() == 4;
        int floor = beside.y();
        // The height the body works from inside: on the step up after a hop, or its own where
        // there is no room to hop.
        int feetIn = jumpRoom ? floor + 1 : floor;
        // A giant is worked from wherever the spiral stands the body, so each log is priced from
        // the lowest of those stands that reaches it. Priced from the cell diagonal to it
        // instead, a dark oak's limbs beside its two tall columns were "reachable" from a stand
        // in a short one, and stayed (2026-09-10).
        List<Pos> stands = giant ? spiral(ring, entry, clockwise, feetIn, logs) : List.of();
        List<Pos> sorted = sortedByHeight(logs);
        int need = Integer.MIN_VALUE;
        boolean fromBeside = true;
        boolean reachable = true;
        for (Pos log : sorted) {
            if (log.y() <= floor) {
                continue;
            }
            OptionalInt feet = giant ? feetAlong(arm, stands, log)
                    : feetToReach(arm, entry.x(), entry.z(), log);
            if (feet.isPresent()) {
                need = Math.max(need, feet.getAsInt());
            } else {
                reachable = false;
            }
            fromBeside &= reaches(arm, beside.x(), floor, beside.z(), log);
        }
        // The furthest block sets the height, and a branch is a block (decision: Luiz,
        // 2026-09-10): one the arm can reach from the trunk at some height counts toward it, and
        // toward going in at all. One it cannot reach from any height is out of reach, and said
        // so at the end.
        List<Pos> past = new ArrayList<>();
        for (Pos branch : branches) {
            OptionalInt feet = giant ? feetAlong(arm, stands, branch)
                    : feetToReach(arm, entry.x(), entry.z(), branch);
            if (feet.isPresent()) {
                need = Math.max(need, feet.getAsInt());
                fromBeside &= reaches(arm, beside.x(), floor, beside.z(), branch);
            } else {
                past.add(branch);
            }
        }
        // A branch past the arm from every stand may be in reach of a body crouched at a stand's
        // edge (decision: Luiz, 2026-09-10): the stand whose crouched eyes are nearest its
        // height — for a lone trunk any height of the column, since the body comes down through
        // every one; for a giant one the spiral makes. Such a branch wants the body inside.
        List<Lean> leans = new ArrayList<>();
        for (Pos branch : past) {
            Pos stand = giant ? leanStand(arm, stands, branch)
                    : leanStand(arm, entry, feetIn, branch);
            if (stand != null) {
                leans.add(new Lean(branch, stand));
                need = Math.max(need, stand.y());
                fromBeside = false;
            }
        }
        if (fromBeside) {
            return fromBeside(arm, sorted, beside, need, ring, branches);
        }
        // Getting in needs something under the stand and nothing but wood or leaves in the
        // body's way — and for a giant one cell more, to hop on from the slot.
        if (!Approach.holds(probe.at(entry.x(), feetIn - 1, entry.z()))) {
            return fromBeside(arm, sorted, beside, need, ring, branches);
        }
        int open = giant ? SLOT : bodyCells;
        List<Pos> stepIn = new ArrayList<>();
        for (int y = feetIn; y < feetIn + open; y++) {
            BlockKind kind = probe.at(entry.x(), y, entry.z());
            if (kind == BlockKind.LOG || kind == BlockKind.LEAVES) {
                stepIn.add(new Pos(entry.x(), y, entry.z()));
            } else if (kind != BlockKind.AIR) {
                return fromBeside(arm, sorted, beside, need, ring, branches);
            }
        }
        Pos stand = new Pos(entry.x(), feetIn, entry.z());
        List<Pos> above = new ArrayList<>();
        List<Pos> under = new ArrayList<>();
        List<Pos> last = new ArrayList<>();
        for (Pos log : sorted) {
            boolean inEntry = log.x() == entry.x() && log.z() == entry.z();
            if (log.y() == floor - 1) {
                last.add(log);
            } else if (log.y() < floor - 1) {
                continue; // buried, and left so
            } else if (inEntry && log.y() < feetIn) {
                under.add(0, log); // highest first: the way down
            } else if (inEntry && log.y() < feetIn + open) {
                continue; // opened on the way in
            } else {
                above.add(log);
            }
        }
        int rises = Math.max(0, need - feetIn);
        return new Climb(stand, need, true, !jumpRoom, stepIn, above, under, last,
                reachable && rises <= MAX_RISES, ring, clockwise, branches, leans);
    }

    /**
     * The height of the entry column a lean reaches {@code branch} from, at or above
     * {@code feetIn}: the one that puts the crouched eyes nearest the branch, since the way out
     * is the same from any. Null when even that one is too far.
     */
    static @Nullable Pos leanStand(Arm arm, Pos entry, int feetIn, Pos branch) {
        int feet = (int) Math.round(branch.y() + 0.5 - arm.crouchedEyeHeight());
        feet = Math.max(feetIn, Math.min(feet, feetIn + MAX_RISES));
        return reachesLeaning(arm, entry.x(), feet, entry.z(), branch)
                ? new Pos(entry.x(), feet, entry.z()) : null;
    }

    /** The one of {@code stands} a lean reaches {@code branch} from most easily; null when none does. */
    static @Nullable Pos leanStand(Arm arm, List<Pos> stands, Pos branch) {
        Pos best = null;
        double nearest = Double.MAX_VALUE;
        for (Pos stand : stands) {
            double d = leaningDistance(arm, stand.x(), stand.y(), stand.z(), branch);
            if (d < nearest && reachesLeaning(arm, stand.x(), stand.y(), stand.z(), branch)) {
                best = stand;
                nearest = d;
            }
        }
        return best;
    }

    /**
     * Everything from where the body already stands: the logs above the floor lowest first, then
     * the ones at floor level and below it, highest first. Incomplete when a log is out of reach
     * from there — a short tree never is; a tall one lands here only when there was no footing to
     * step in on.
     */
    private static Climb fromBeside(Arm arm, List<Pos> sorted, Pos beside, int need,
                                    List<Pos> ring, List<Pos> branches) {
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
        return new Climb(beside, need, false, false, List.of(), above, List.of(), last, complete,
                ring, false, branches, List.of());
    }

    /**
     * Where a giant's spiral stands the body, lowest first: the entry stand, then one column
     * round and one cell up for as long as that column has a log at the height the body hops
     * from — its stair. Columns of unequal height end it early; a dark oak's are.
     */
    static List<Pos> spiral(List<Pos> ring, Pos entry, boolean clockwise, int feetIn,
                            List<Pos> logs) {
        List<Pos> stands = new ArrayList<>();
        int from = Math.max(0, ring.indexOf(entry));
        int step = clockwise ? 1 : -1;
        for (int k = 0; k <= MAX_RISES; k++) {
            Pos column = ring.get(Math.floorMod(from + step * k, ring.size()));
            int feet = feetIn + k;
            if (k > 0 && !logs.contains(new Pos(column.x(), feet - 1, column.z()))) {
                break;
            }
            stands.add(new Pos(column.x(), feet, column.z()));
        }
        return stands;
    }

    /** The feet of the lowest of {@code stands} the arm reaches {@code cell} from; empty when none. */
    static OptionalInt feetAlong(Arm arm, List<Pos> stands, Pos cell) {
        for (Pos stand : stands) {
            if (reaches(arm, stand.x(), stand.y(), stand.z(), cell)) {
                return OptionalInt.of(stand.y());
            }
        }
        return OptionalInt.empty();
    }

    private static List<Pos> sortedByHeight(List<Pos> logs) {
        List<Pos> sorted = new ArrayList<>(logs);
        sorted.sort((a, b) -> a.y() != b.y() ? Integer.compare(a.y(), b.y())
                : a.x() != b.x() ? Integer.compare(a.x(), b.x())
                : Integer.compare(a.z(), b.z()));
        return sorted;
    }

    // ── the columns ──────────────────────────────────────────────────────────────────────────

    /**
     * The columns clockwise from above — north-west, north-east, south-east, south-west, as
     * {@code +x} is east and {@code +z} south — so that {@link #next} can walk the ring. Fewer than
     * four keep the order they came in.
     */
    static List<Pos> ringOf(List<Pos> columns) {
        if (columns.size() != 4) {
            return List.copyOf(columns);
        }
        int x0 = Integer.MAX_VALUE;
        int z0 = Integer.MAX_VALUE;
        for (Pos c : columns) {
            x0 = Math.min(x0, c.x());
            z0 = Math.min(z0, c.z());
        }
        List<Pos> ring = new ArrayList<>(4);
        for (int[] d : new int[][] {{0, 0}, {1, 0}, {1, 1}, {0, 1}}) {
            for (Pos c : columns) {
                if (c.x() == x0 + d[0] && c.z() == z0 + d[1]) {
                    ring.add(c);
                }
            }
        }
        return ring.size() == 4 ? ring : List.copyOf(columns);
    }

    /** Whether {@code cell} is this tree's own wood by the plan: in a column, or a branch. */
    public boolean owns(Pos cell) {
        return branches.contains(cell) || column(cell) != null;
    }

    /** Whether this is a 2×2 to spiral rather than a lone trunk to climb. */
    public boolean giant() {
        return columns.size() == 4;
    }

    /** The column {@code at} stands in, by its x and z; null when it is not in the trunk. */
    public @Nullable Pos column(Pos at) {
        for (Pos c : columns) {
            if (c.x() == at.x() && c.z() == at.z()) {
                return c;
            }
        }
        return null;
    }

    /**
     * The column after {@code column} round the ring — the spiral's way when {@code forward},
     * the way back down otherwise.
     */
    public Pos next(Pos column, boolean forward) {
        int i = columns.indexOf(column);
        int step = forward == clockwise ? 1 : -1;
        return columns.get(Math.floorMod(i + step, columns.size()));
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

    /** How many times the body goes up one: blocks placed underfoot, or slots of the spiral. */
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
     * {@code "open 3 · step in · spiral 5 · 44 to break · 1 underfoot"} or
     * {@code "4 to break from beside"}.
     */
    public String describe() {
        String tail = complete ? ""
                : !stepsIn && needFeetY > stand.y() ? " · no footing to step in"
                : " · some out of reach";
        if (!stepsIn) {
            return (above.size() + last.size()) + " to break from beside"
                    + (branches.isEmpty() ? "" : " · " + branches.size() + " branches") + tail;
        }
        int rises = rises();
        String up = giant() ? "spiral " + rises
                : rises == 0 ? "no rise" : rises == 1 ? "1 rise" : rises + " rises";
        return "open " + stepIn.size() + (digsIn ? " · dig in · " : " · step in · ") + up
                + " · " + above.size() + (giant() ? " to break" : " above")
                + (branches.isEmpty() ? "" : " · " + branches.size() + " branches")
                + (leans.isEmpty() ? "" : " · " + leans.size() + " leaning")
                + (under.isEmpty() ? "" : " · " + under.size() + " underfoot")
                + (last.isEmpty() ? "" : " · then " + last.size() + " from outside") + tail;
    }
}
