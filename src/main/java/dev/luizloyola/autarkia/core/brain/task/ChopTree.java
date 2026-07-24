package dev.luizloyola.autarkia.core.brain.task;

import dev.luizloyola.autarkia.core.brain.BrainContext;
import dev.luizloyola.autarkia.core.brain.act.BlockBreaker;
import dev.luizloyola.autarkia.core.brain.act.BreakState;
import dev.luizloyola.autarkia.core.brain.act.MoveState;
import dev.luizloyola.autarkia.core.brain.act.ScaffoldState;
import dev.luizloyola.autarkia.core.brain.knowledge.BlockKind;
import dev.luizloyola.autarkia.core.brain.knowledge.BlockProbe;
import dev.luizloyola.autarkia.core.brain.knowledge.PoiKind;
import dev.luizloyola.autarkia.core.brain.knowledge.PoiMemory;
import dev.luizloyola.autarkia.core.brain.knowledge.RegionGrowth;
import dev.luizloyola.autarkia.core.brain.knowledge.TreeRule;
import dev.luizloyola.autarkia.core.brain.sense.Drop;
import dev.luizloyola.autarkia.core.brain.sense.Pos;
import dev.luizloyola.autarkia.core.log.Category;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Chop one whole tree: approach the remembered anchor, re-scan ({@link RegionGrowth} re-run at
 * task time), individuate our tree ({@link TreeSurvey}; no grounded trunk → not a tree →
 * forget), fell the trunk from the second log up and the branches outermost-first, retry
 * stragglers from atop the stump, free canopy-stranded drops, un-build the climb, fell the
 * stump, collect by flock ({@link Flocks}), and replant one sapling per stump log — fishing the
 * leftover canopy for one first if the pack has none.
 *
 * <p>The reach ladder, per blocked swing: break the first obstruction on the arm's path, a leaf
 * or a weed (bounded per target) → WALK once → <b>PILLAR up</b> one nerd-pole step on her own
 * logs ({@link dev.luizloyola.autarkia.core.brain.act.Scaffolder}). Every placed pillar cell is
 * remembered and un-built on the way down (broken underfoot, drops reclaimed) — she leaves no
 * towers. Building up is what makes bottom-up felling safe: nothing stays out of reach, so no
 * floating remnants get manufactured.
 *
 * <p>Memory writes on the way out: ghost or ungrounded blob → forget + FAILED; felled → forget +
 * SUCCESS; real-but-unworkable → memory kept, FAILED, anchor AVOIDED a while so retries rotate
 * targets. {@link #failureDetail()} reports the ending.
 */
public final class ChopTree implements PrimitiveTask {
    /** Close enough to the anchor to count as "at the tree" — the chop loop steps the rest. */
    public static final int APPROACH_NEAR = 6;
    /** Matches the sensor's own per-tick read budget. */
    public static final int SCAN_READS_PER_TICK = 64;
    /** Safety cap on leaves broken while freeing stranded drops. */
    public static final int FREED_LEAVES_LIMIT = 32;
    /** Ground margin around the tree bounds that still counts as the collection area. */
    public static final int COLLECT_MARGIN = 3;
    /** Obstruction-clearing swings allowed per log target (leaves/weeds in the arm's path). */
    public static final int CLEARS_PER_TARGET = 4;
    /** Nerd-pole height cap for one chop — taller than any vanilla tree's working need. */
    public static final int PILLAR_MAX = 12;
    /** Leaves broken while fishing for a replant sapling before giving up. */
    public static final int FISH_LIMIT = 24;
    /** Ticks an unworkable tree's anchor stays avoided, so retries rotate targets. */
    public static final int AVOID_TICKS = 6000;

    private enum Phase {
        APPROACH, SCAN, TRUNK, BRANCHES, MOUNT, STUMP_BRANCHES, FREE_ITEMS, DESCEND, STUMP,
        COLLECT, FISH, REPLANT
    }

    private final PoiMemory memory;
    private final boolean replant;

    private Phase phase = Phase.APPROACH;
    private RegionGrowth scan;
    private TreeSurvey.Tree tree;
    private final Deque<Pos> queue = new ArrayDeque<>();
    /** Branches unreachable from the ground, retried from atop the stump. */
    private final List<Pos> deferred = new ArrayList<>();
    private final Set<Pos> triedStranded = new HashSet<>();
    /** Pillar cells placed this chop, newest first — un-built in DESCEND. */
    private final Deque<Pos> pillar = new ArrayDeque<>();
    /** LEAVES cells from the arrival scan — the sapling-fishing menu. */
    private final List<Pos> canopy = new ArrayList<>();

    /** The plain one-liner for a FAILED ending — what {@link #failureDetail()} reports. */
    private String ending;

    private Pos target;
    private Pos clearTarget;
    private int clearsUsed;
    private boolean walked;
    private boolean walkIssued;
    private boolean breaking;
    private int felled;
    private int skipped;
    private int freedLeaves;
    private int fished;
    private int collectLaps;
    private int collectLapCap = -1;
    /** The species chopped, learned from the first log drop seen — names the sapling to plant. */
    private String speciesLogId;
    private final Deque<Pos> replantSites = new ArrayDeque<>();
    private int planted;

    public ChopTree(PoiMemory memory, boolean replant) {
        this.memory = memory;
        this.replant = replant;
    }

    @Override
    public TaskStatus tick(BrainContext ctx) {
        return switch (phase) {
            case APPROACH -> approach(ctx);
            case SCAN -> scan(ctx);
            case TRUNK, BRANCHES, STUMP_BRANCHES, STUMP -> chop(ctx);
            case MOUNT -> mount(ctx);
            case FREE_ITEMS -> freeItems(ctx);
            case DESCEND -> descend(ctx);
            case COLLECT -> collect(ctx);
            case FISH -> fish(ctx);
            case REPLANT -> replant(ctx);
        };
    }

    @Override
    public void cancel(BrainContext ctx) {
        ctx.actuators().breaker().abort();
        ctx.actuators().mover().stop();
        ctx.actuators().scaffolder().abort();
    }

    @Override
    public String failureDetail() {
        Pos a = memory.anchor();
        return "chop TREE (" + a.x() + ", " + a.y() + ", " + a.z() + "): "
                + (ending != null ? ending : "failed");
    }

    @Override
    public String describe() {
        Pos a = memory.anchor();
        return "chop TREE (" + a.x() + ", " + a.y() + ", " + a.z() + "): "
                + phase.name().toLowerCase(Locale.ROOT)
                + (felled > 0 ? " (" + felled + " felled)" : "");
    }

    // --- phases ----------------------------------------------------------------------------------

    private TaskStatus approach(BrainContext ctx) {
        Pos here = ctx.percepts().position();
        if (horizontalDistSq(here, memory.anchor()) <= (long) APPROACH_NEAR * APPROACH_NEAR) {
            if (walkIssued) {
                ctx.actuators().mover().stop();
                walkIssued = false;
            }
            phase = Phase.SCAN;
            return TaskStatus.RUNNING;
        }
        if (!walkIssued) {
            Pos a = memory.anchor();
            ctx.actuators().mover().moveTo(a.x(), a.y(), a.z());
            walkIssued = true;
            return TaskStatus.RUNNING;
        }
        if (ctx.actuators().mover().state() == MoveState.MOVING) {
            return TaskStatus.RUNNING;
        }
        walkIssued = false;
        if (horizontalDistSq(ctx.percepts().position(), memory.anchor())
                <= (long) (APPROACH_NEAR * 2) * (APPROACH_NEAR * 2)) {
            phase = Phase.SCAN;
            return TaskStatus.RUNNING;
        }
        return unworkable(ctx, "could not reach the tree");
    }

    private TaskStatus scan(BrainContext ctx) {
        BlockProbe probe = ctx.percepts().blocks();
        if (scan == null) {
            Optional<Pos> seed = findSeed(probe);
            if (seed.isEmpty()) {
                return ghost(ctx);
            }
            scan = new RegionGrowth(TreeRule.INSTANCE, seed.get(),
                    probe.at(seed.get().x(), seed.get().y(), seed.get().z()));
        }
        scan.step(probe, SCAN_READS_PER_TICK);
        if (!scan.isDone()) {
            return TaskStatus.RUNNING;
        }
        for (var entry : scan.result().blocks().entrySet()) {
            if (entry.getValue() == BlockKind.LEAVES) {
                canopy.add(entry.getKey());
            }
        }
        List<TreeSurvey.Tree> trees = TreeSurvey.survey(scan.result().blocks(), probe);
        tree = TreeSurvey.nearest(trees, memory.anchor()).orElse(null);
        if (tree == null) {
            // No grounded trunk in the blob: not a tree anymore (decision: Luiz). Heals legacy
            // floater memories on first visit, and perception won't re-remember them.
            return ghost(ctx);
        }
        ctx.journal().record(Category.BRAIN, "chop",
                "felling (" + tree.logCount() + " logs, " + tree.branches().size() + " branches)");
        queue.addAll(tree.upper());
        phase = Phase.TRUNK;
        return TaskStatus.RUNNING;
    }

    /**
     * The shared per-target machine with the reach ladder: swing → clear the arm's path
     * (bounded) → walk once → pillar up (bounded, her own logs) → give up on this log.
     */
    private TaskStatus chop(BrainContext ctx) {
        BlockBreaker breaker = ctx.actuators().breaker();
        if (breaking) {
            switch (breaker.state()) {
                case BREAKING:
                    return TaskStatus.RUNNING;
                case FINISHED:
                    breaking = false;
                    if (clearTarget != null) {
                        clearTarget = null; // sightline cleared — swing at the real target again
                    } else {
                        felled++;
                        target = null;
                    }
                    return TaskStatus.RUNNING;
                default:
                    breaking = false;
                    if (clearTarget != null) {
                        clearTarget = null;
                    } else {
                        skipped++;
                        target = null;
                    }
                    return TaskStatus.RUNNING;
            }
        }
        if (ctx.actuators().scaffolder().state() == ScaffoldState.RISING) {
            return TaskStatus.RUNNING;
        }
        if (walkIssued) {
            if (ctx.actuators().mover().state() == MoveState.MOVING) {
                return TaskStatus.RUNNING;
            }
            walkIssued = false;
        }
        if (target == null) {
            if (queue.isEmpty()) {
                return advancePhase(ctx);
            }
            target = queue.poll();
            walked = false;
            clearsUsed = 0;
        }
        if (ctx.percepts().blocks().at(target.x(), target.y(), target.z()) != BlockKind.LOG) {
            target = null; // already gone — not ours to count
            return TaskStatus.RUNNING;
        }
        if (breaker.begin(target)) {
            breaking = true;
            return TaskStatus.RUNNING;
        }
        // Refused. First suspicion: something in the arm's way — break it if it will break.
        Pos blocker = firstObstruction(ctx, target);
        if (blocker != null && clearsUsed < CLEARS_PER_TARGET && breaker.begin(blocker)) {
            breaking = true;
            clearTarget = blocker;
            clearsUsed++;
            return TaskStatus.RUNNING;
        }
        // Clear path (or unclearable blocker): distance. Walk once, then climb.
        if (blocker == null && !walked && phase != Phase.STUMP_BRANCHES) {
            walked = true;
            ctx.actuators().mover().moveTo(target.x(), target.y(), target.z());
            walkIssued = true;
            return TaskStatus.RUNNING;
        }
        if (pillar.size() < PILLAR_MAX) {
            String block = pillarBlock(ctx);
            if (block != null) {
                Pos feet = ctx.percepts().position();
                if (ctx.actuators().scaffolder().up(block)) {
                    pillar.push(feet); // the cell being vacated receives the block
                    return TaskStatus.RUNNING;
                }
            }
        }
        if (phase == Phase.BRANCHES) {
            deferred.add(target); // one more chance, from atop the stump
        } else {
            skipped++;
        }
        target = null;
        return TaskStatus.RUNNING;
    }

    private TaskStatus mount(BrainContext ctx) {
        if (!walkIssued) {
            Pos top = tree.base().get(0);
            ctx.actuators().mover().moveTo(top.x(), top.y() + 1, top.z());
            walkIssued = true;
            return TaskStatus.RUNNING;
        }
        if (ctx.actuators().mover().state() == MoveState.MOVING) {
            return TaskStatus.RUNNING;
        }
        walkIssued = false;
        queue.addAll(deferred);
        deferred.clear();
        phase = Phase.STUMP_BRANCHES;
        return TaskStatus.RUNNING;
    }

    private TaskStatus freeItems(BrainContext ctx) {
        BlockBreaker breaker = ctx.actuators().breaker();
        if (breaking) {
            if (breaker.state() == BreakState.BREAKING) {
                return TaskStatus.RUNNING;
            }
            breaking = false; // leaf broke (or died) — either way re-evaluate the canopy
            return TaskStatus.RUNNING;
        }
        if (freedLeaves >= FREED_LEAVES_LIMIT) {
            phase = Phase.DESCEND;
            return TaskStatus.RUNNING;
        }
        BlockProbe probe = ctx.percepts().blocks();
        Pos here = ctx.percepts().position();
        Pos bestLeaf = null;
        long bestDist = Long.MAX_VALUE;
        for (Drop drop : ctx.percepts().drops()) {
            noteSpecies(drop);
            if (!inArea(drop.pos(), 1) || triedStranded.contains(drop.pos())) {
                continue;
            }
            Pos below = new Pos(drop.pos().x(), drop.pos().y() - 1, drop.pos().z());
            if (probe.at(below.x(), below.y(), below.z()) != BlockKind.LEAVES) {
                continue; // not stranded — the collect phase's business
            }
            long dist = distSq(here, below);
            if (dist < bestDist) {
                bestDist = dist;
                bestLeaf = below;
            }
        }
        if (bestLeaf == null) {
            phase = Phase.DESCEND;
            return TaskStatus.RUNNING;
        }
        if (breaker.begin(bestLeaf)) {
            breaking = true;
            freedLeaves++;
        } else {
            triedStranded.add(new Pos(bestLeaf.x(), bestLeaf.y() + 1, bestLeaf.z()));
        }
        return TaskStatus.RUNNING;
    }

    /** Un-build the nerd-pole, newest block first, standing on each — she leaves no towers. */
    private TaskStatus descend(BrainContext ctx) {
        BlockBreaker breaker = ctx.actuators().breaker();
        if (breaking) {
            if (breaker.state() == BreakState.BREAKING) {
                return TaskStatus.RUNNING;
            }
            breaking = false;
            if (!pillar.isEmpty()) {
                pillar.pop(); // broken underfoot; the drop reclaims via walk-over/collect
            }
            return TaskStatus.RUNNING;
        }
        if (pillar.isEmpty()) {
            queue.addAll(tree.base()); // breaking the block under her own feet is allowed —
            phase = Phase.STUMP;       // she drops one block, exactly like a player would
            return TaskStatus.RUNNING;
        }
        Pos step = pillar.peek();
        if (ctx.percepts().blocks().at(step.x(), step.y(), step.z()) == BlockKind.AIR) {
            pillar.pop(); // never landed, or someone beat her to it
            return TaskStatus.RUNNING;
        }
        if (breaker.begin(step)) {
            breaking = true;
            return TaskStatus.RUNNING;
        }
        if (!walkIssued) {
            ctx.actuators().mover().moveTo(step.x(), step.y() + 1, step.z());
            walkIssued = true;
            return TaskStatus.RUNNING;
        }
        if (ctx.actuators().mover().state() == MoveState.MOVING) {
            return TaskStatus.RUNNING;
        }
        walkIssued = false;
        pillar.pop(); // can't get back to it — leave one block rather than loop forever
        skipped++;
        return TaskStatus.RUNNING;
    }

    private TaskStatus collect(BrainContext ctx) {
        List<Pos> ground = new ArrayList<>();
        BlockProbe probe = ctx.percepts().blocks();
        for (Drop drop : ctx.percepts().drops()) {
            noteSpecies(drop);
            if (inArea(drop.pos(), COLLECT_MARGIN)
                    && probe.at(drop.pos().x(), drop.pos().y() - 1, drop.pos().z()) != BlockKind.LEAVES) {
                ground.add(drop.pos());
            }
        }
        if (collectLapCap < 0) {
            collectLapCap = Flocks.count(ground) * 3 + 6;
        }
        if (ground.isEmpty() || collectLaps >= collectLapCap) {
            if (replant && !tree.base().isEmpty()) {
                if (walkIssued) {
                    ctx.actuators().mover().stop();
                    walkIssued = false;
                }
                replantSites.addAll(tree.base());
                phase = Phase.FISH;
                return TaskStatus.RUNNING;
            }
            return finish(ctx);
        }
        if (walkIssued && ctx.actuators().mover().state() == MoveState.MOVING) {
            return TaskStatus.RUNNING;
        }
        Pos centroid = Flocks.nearestCentroid(ground, ctx.percepts().position());
        ctx.actuators().mover().moveTo(centroid.x(), centroid.y(), centroid.z());
        walkIssued = true;
        collectLaps++;
        return TaskStatus.RUNNING;
    }

    /**
     * No sapling in the pack: break an accessible leftover leaf, walk to whatever dropped, and
     * repeat until a sapling lands or the canopy (or the budget) runs out. Replanting is a
     * courtesy — running dry skips, never fails.
     */
    private TaskStatus fish(BrainContext ctx) {
        String sapling = speciesLogId == null ? null : saplingFor(speciesLogId);
        if (sapling == null || ctx.percepts().inventory().count(sapling) > 0
                || fished >= FISH_LIMIT) {
            phase = Phase.REPLANT;
            return TaskStatus.RUNNING;
        }
        BlockBreaker breaker = ctx.actuators().breaker();
        if (breaking) {
            if (breaker.state() == BreakState.BREAKING) {
                return TaskStatus.RUNNING;
            }
            breaking = false;
            return TaskStatus.RUNNING;
        }
        if (walkIssued) {
            if (ctx.actuators().mover().state() == MoveState.MOVING) {
                return TaskStatus.RUNNING;
            }
            walkIssued = false;
        }
        // A dropped sapling in sight beats breaking more leaves — go get it.
        BlockProbe probe = ctx.percepts().blocks();
        for (Drop drop : ctx.percepts().drops()) {
            if (drop.itemId().equals(sapling) && inArea(drop.pos(), COLLECT_MARGIN)) {
                ctx.actuators().mover().moveTo(drop.pos().x(), drop.pos().y(), drop.pos().z());
                walkIssued = true;
                return TaskStatus.RUNNING;
            }
        }
        while (!canopy.isEmpty()) {
            Pos leaf = canopy.remove(canopy.size() - 1);
            if (probe.at(leaf.x(), leaf.y(), leaf.z()) != BlockKind.LEAVES) {
                continue; // already broken or decayed
            }
            if (breaker.begin(leaf)) {
                breaking = true;
                fished++;
                return TaskStatus.RUNNING;
            }
            // out of the arm's reach — try the next leftover leaf
        }
        phase = Phase.REPLANT; // canopy exhausted, still saplingless: the courtesy will skip
        return TaskStatus.RUNNING;
    }

    /**
     * One sapling per stump log — 1×1 vs 2×2 falls out of the base size. Planted last so
     * saplings gathered during this chop count.
     */
    private TaskStatus replant(BrainContext ctx) {
        if (walkIssued) {
            if (ctx.actuators().mover().state() == MoveState.MOVING) {
                return TaskStatus.RUNNING;
            }
            walkIssued = false;
        }
        if (target == null) {
            if (replantSites.isEmpty()) {
                if (planted > 0) {
                    ctx.journal().record(Category.BRAIN, "chop", "replanted " + planted + " sapling"
                            + (planted == 1 ? "" : "s"));
                }
                return finish(ctx);
            }
            target = replantSites.poll();
            walked = false;
        }
        String sapling = speciesLogId == null ? null : saplingFor(speciesLogId);
        if (sapling == null || ctx.percepts().inventory().count(sapling) == 0) {
            ctx.journal().record(Category.BRAIN, "chop", "replant skipped (no sapling in the pack)");
            target = null;
            replantSites.clear();
            return finish(ctx);
        }
        if (ctx.actuators().placer().place(sapling, target)) {
            planted++;
            target = null;
            return TaskStatus.RUNNING;
        }
        if (!walked) {
            walked = true;
            ctx.actuators().mover().moveTo(target.x(), target.y(), target.z());
            walkIssued = true;
            return TaskStatus.RUNNING;
        }
        target = null; // site blocked or out of reach even after the walk — let it go
        return TaskStatus.RUNNING;
    }

    // --- transitions -----------------------------------------------------------------------------

    private TaskStatus advancePhase(BrainContext ctx) {
        switch (phase) {
            case TRUNK -> {
                queue.addAll(tree.branches());
                phase = Phase.BRANCHES;
            }
            case BRANCHES -> {
                if (!deferred.isEmpty() && !tree.base().isEmpty()) {
                    phase = Phase.MOUNT;
                } else {
                    skipped += deferred.size();
                    deferred.clear();
                    phase = Phase.FREE_ITEMS;
                }
            }
            case STUMP_BRANCHES -> phase = Phase.FREE_ITEMS;
            case STUMP -> {
                ctx.journal().record(Category.BRAIN, "chop", "felled (" + felled + " logs"
                        + (skipped > 0 ? ", " + skipped + " out of reach" : "") + ")");
                phase = Phase.COLLECT;
            }
            default -> throw new IllegalStateException("no advance from " + phase);
        }
        return TaskStatus.RUNNING;
    }

    private TaskStatus finish(BrainContext ctx) {
        if (felled > 0) {
            ctx.knowledge().forget(PoiKind.TREE, memory.anchor());
            return TaskStatus.SUCCESS;
        }
        return unworkable(ctx, "nothing reachable to fell");
    }

    /** A real tree she cannot work right now: keep the memory, avoid it a while, fail outright. */
    private TaskStatus unworkable(BrainContext ctx, String why) {
        ending = why;
        ctx.knowledge().avoid(PoiKind.TREE, memory.anchor(), ctx.percepts().time() + AVOID_TICKS);
        ctx.journal().record(Category.BRAIN, "chop", why + " — avoiding it a while");
        return TaskStatus.FAILED;
    }

    private TaskStatus ghost(BrainContext ctx) {
        Pos a = memory.anchor();
        ctx.knowledge().forget(PoiKind.TREE, a);
        ending = "no tree here anymore — forgot it";
        ctx.journal().record(Category.BRAIN, "chop",
                "grove gone — forgot TREE (" + a.x() + ", " + a.y() + ", " + a.z() + ")");
        return TaskStatus.FAILED;
    }

    // --- helpers ---------------------------------------------------------------------------------

    /**
     * First blocked cell on the arm's straight path (eye height over the feet cell) to the
     * target's center, or null when clear — used to pick what to CLEAR. Leaves and weeds read as
     * blockers; both break in a swing.
     */
    private Pos firstObstruction(BrainContext ctx, Pos to) {
        BlockProbe probe = ctx.percepts().blocks();
        Pos feet = ctx.percepts().position();
        double fx = feet.x() + 0.5;
        double fy = feet.y() + 1.6;
        double fz = feet.z() + 0.5;
        double tx = to.x() + 0.5;
        double ty = to.y() + 0.5;
        double tz = to.z() + 0.5;
        double dist = Math.sqrt((tx - fx) * (tx - fx) + (ty - fy) * (ty - fy) + (tz - fz) * (tz - fz));
        int steps = (int) Math.ceil(dist * 2.0);
        for (int i = 1; i < steps; i++) {
            double t = i / (double) steps;
            Pos cell = new Pos((int) Math.floor(fx + (tx - fx) * t),
                    (int) Math.floor(fy + (ty - fy) * t), (int) Math.floor(fz + (tz - fz) * t));
            if (cell.equals(to) || cell.equals(feet)
                    || (cell.x() == feet.x() && cell.y() == feet.y() + 1 && cell.z() == feet.z())) {
                continue;
            }
            BlockKind kind = probe.at(cell.x(), cell.y(), cell.z());
            if (kind != BlockKind.AIR && kind != BlockKind.WATER) {
                return cell;
            }
        }
        return null;
    }

    /** A carried block she may pillar with: her own logs. */
    private String pillarBlock(BrainContext ctx) {
        if (speciesLogId != null && ctx.percepts().inventory().count(speciesLogId) > 0) {
            return speciesLogId;
        }
        for (var entry : ctx.percepts().inventory().occupied()) {
            String id = entry.stack().id();
            if (id.endsWith("_log") || id.endsWith("_stem")) {
                return id;
            }
        }
        return null;
    }

    /** Remembers the chopped species from the first log-ish drop sighted. */
    private void noteSpecies(Drop drop) {
        if (speciesLogId == null
                && (drop.itemId().endsWith("_log") || drop.itemId().endsWith("_stem"))) {
            speciesLogId = drop.itemId();
        }
    }

    /**
     * The sapling that regrows this log. String-level vanilla knowledge with the irregular
     * families special-cased — provisional until a compat materials lens exists.
     */
    static String saplingFor(String logId) {
        if (logId.endsWith("mangrove_log")) {
            return "minecraft:mangrove_propagule";
        }
        if (logId.endsWith("crimson_stem")) {
            return "minecraft:crimson_fungus";
        }
        if (logId.endsWith("warped_stem")) {
            return "minecraft:warped_fungus";
        }
        if (logId.endsWith("_log")) {
            return logId.substring(0, logId.length() - "_log".length()) + "_sapling";
        }
        return null;
    }

    /** Seed for the re-scan: the anchor, or a cell just above it (the anchor may be chopped). */
    private Optional<Pos> findSeed(BlockProbe probe) {
        Pos a = memory.anchor();
        for (int dy = 0; dy <= 2; dy++) {
            Pos p = new Pos(a.x(), a.y() + dy, a.z());
            BlockKind kind = probe.at(p.x(), p.y(), p.z());
            if (kind == BlockKind.LOG || kind == BlockKind.LEAVES) {
                return Optional.of(p);
            }
        }
        return Optional.empty();
    }

    private boolean inArea(Pos p, int margin) {
        return p.x() >= memory.bounds().min().x() - margin && p.x() <= memory.bounds().max().x() + margin
                && p.y() >= memory.bounds().min().y() - margin && p.y() <= memory.bounds().max().y() + margin
                && p.z() >= memory.bounds().min().z() - margin && p.z() <= memory.bounds().max().z() + margin;
    }

    private static long horizontalDistSq(Pos a, Pos b) {
        long dx = a.x() - b.x();
        long dz = a.z() - b.z();
        return dx * dx + dz * dz;
    }

    private static long distSq(Pos a, Pos b) {
        long dx = a.x() - b.x();
        long dy = a.y() - b.y();
        long dz = a.z() - b.z();
        return dx * dx + dy * dy + dz * dz;
    }
}
