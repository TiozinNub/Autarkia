package dev.luizloyola.autarkia.core.brain.task;

import dev.luizloyola.autarkia.core.brain.BrainContext;
import dev.luizloyola.autarkia.core.brain.act.BlockBreaker;
import dev.luizloyola.autarkia.core.brain.act.BreakState;
import dev.luizloyola.autarkia.core.brain.act.MoveState;
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
import java.util.Optional;
import java.util.Set;

/**
 * Chop one whole tree, phase by phase: walk to the remembered anchor, re-scan the structure
 * ({@link RegionGrowth} re-run at task time — memory points, world is truth), individuate our
 * tree out of the grove ({@link TreeSurvey}), then the trunk from the second log up (the stump
 * stays: climbing platform and replant marker), branches from the ground and the rest from atop
 * the stump, free canopy-stranded drops by breaking the leaves under them, fell the stump,
 * collect by walking flock centroids ({@link Flocks}), and — once the placer exists (build 2b) —
 * replant one sapling per stump log.
 *
 * <p>Memory writes on the way out: a ghost grove (no logs in the scan) is forgotten and FAILED;
 * a felled tree is forgotten on SUCCESS; a real-but-unreachable one keeps its memory and fails
 * outright, retry pacing being the caller's job. Unreachable logs are skipped and left floating.
 *
 * <p>One Navigator-style state machine on four ports (probe, breaker, mover, drops); the heavy
 * algorithms live beside it as pure, separately-tested classes.
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

    private enum Phase {
        APPROACH, SCAN, TRUNK, BRANCHES, MOUNT, STUMP_BRANCHES, FREE_ITEMS, STUMP, COLLECT
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

    private Pos target;
    private boolean walked;
    private boolean walkIssued;
    private boolean breaking;
    private int felled;
    private int skipped;
    private int freedLeaves;
    private int collectLaps;
    private int collectLapCap = -1;

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
            case COLLECT -> collect(ctx);
        };
    }

    @Override
    public void cancel(BrainContext ctx) {
        ctx.actuators().breaker().abort();
        ctx.actuators().mover().stop();
    }

    @Override
    public String describe() {
        Pos a = memory.anchor();
        return "chop TREE (" + a.x() + ", " + a.y() + ", " + a.z() + "): "
                + phase.name().toLowerCase(java.util.Locale.ROOT)
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
        MoveState state = ctx.actuators().mover().state();
        if (state == MoveState.MOVING) {
            return TaskStatus.RUNNING;
        }
        walkIssued = false;
        // The anchor is a solid trunk cell, so the walk legitimately ends NEAR it (partial
        // path). Anywhere in the loose vicinity is workable — the chop loop steps the rest.
        if (horizontalDistSq(ctx.percepts().position(), memory.anchor())
                <= (long) (APPROACH_NEAR * 2) * (APPROACH_NEAR * 2)) {
            phase = Phase.SCAN;
            return TaskStatus.RUNNING;
        }
        ctx.journal().record(Category.BRAIN, "chop", "could not reach the tree");
        return TaskStatus.FAILED; // memory kept: the tree is (as far as she knows) real
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
        List<TreeSurvey.Tree> trees = TreeSurvey.survey(scan.result().blocks(), probe);
        tree = TreeSurvey.nearest(trees, memory.anchor()).orElse(null);
        if (tree == null) {
            // No grounded trunk anywhere: a floating remnant. Take what we can reach — all its
            // logs as branches of a virtual trunk; no stump, no mount, no replant.
            List<Pos> logs = new ArrayList<>();
            for (var entry : scan.result().blocks().entrySet()) {
                if (entry.getValue() == BlockKind.LOG) {
                    logs.add(entry.getKey());
                }
            }
            if (logs.isEmpty()) {
                return ghost(ctx);
            }
            logs.sort(java.util.Comparator.comparingInt(Pos::y));
            tree = new TreeSurvey.Tree(List.of(), List.of(), logs);
        }
        ctx.journal().record(Category.BRAIN, "chop",
                "felling (" + tree.logCount() + " logs, " + tree.branches().size() + " branches)");
        queue.addAll(tree.upper());
        phase = Phase.TRUNK;
        return TaskStatus.RUNNING;
    }

    /** The shared move-if-needed-then-break loop for every log phase. */
    private TaskStatus chop(BrainContext ctx) {
        BlockBreaker breaker = ctx.actuators().breaker();
        if (breaking) {
            switch (breaker.state()) {
                case BREAKING:
                    return TaskStatus.RUNNING;
                case FINISHED:
                    felled++;
                    breaking = false;
                    target = null;
                    return TaskStatus.RUNNING;
                default: // FAILED or stopped out from under us: this log is over, move on
                    breaking = false;
                    skipped++;
                    target = null;
                    return TaskStatus.RUNNING;
            }
        }
        if (walkIssued) {
            if (ctx.actuators().mover().state() == MoveState.MOVING) {
                return TaskStatus.RUNNING;
            }
            walkIssued = false; // terminal either way: retry the swing from wherever we stand
        }
        if (target == null) {
            if (queue.isEmpty()) {
                return advancePhase(ctx);
            }
            target = queue.poll();
            walked = false;
        }
        if (ctx.percepts().blocks().at(target.x(), target.y(), target.z()) != BlockKind.LOG) {
            target = null; // already gone (decayed, another chopper) — not ours to count
            return TaskStatus.RUNNING;
        }
        if (breaker.begin(target)) {
            breaking = true;
            return TaskStatus.RUNNING;
        }
        // Refused = out of reach. Walk toward it once (stump-branch phase never walks: the
        // whole point of standing up there), then give up on this log.
        if (!walked && phase != Phase.STUMP_BRANCHES) {
            walked = true;
            ctx.actuators().mover().moveTo(target.x(), target.y(), target.z());
            walkIssued = true;
            return TaskStatus.RUNNING;
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
        // Mounted or not, proceed: refusals in the stump-branch phase just skip those logs.
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
            return startStump(ctx);
        }
        BlockProbe probe = ctx.percepts().blocks();
        Pos here = ctx.percepts().position();
        Pos bestLeaf = null;
        long bestDist = Long.MAX_VALUE;
        for (Drop drop : ctx.percepts().drops()) {
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
            return startStump(ctx);
        }
        if (breaker.begin(bestLeaf)) {
            breaking = true;
            freedLeaves++;
        } else {
            // Out of reach even from up here: that drop rides the canopy's natural decay.
            triedStranded.add(new Pos(bestLeaf.x(), bestLeaf.y() + 1, bestLeaf.z()));
        }
        return TaskStatus.RUNNING;
    }

    private TaskStatus collect(BrainContext ctx) {
        List<Pos> ground = new ArrayList<>();
        BlockProbe probe = ctx.percepts().blocks();
        for (Drop drop : ctx.percepts().drops()) {
            if (inArea(drop.pos(), COLLECT_MARGIN)
                    && probe.at(drop.pos().x(), drop.pos().y() - 1, drop.pos().z()) != BlockKind.LEAVES) {
                ground.add(drop.pos());
            }
        }
        if (collectLapCap < 0) {
            collectLapCap = Flocks.count(ground) * 3 + 6;
        }
        if (ground.isEmpty() || collectLaps >= collectLapCap) {
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
                if (replant) {
                    // Build 2b: BlockPlacer + one sapling per stump log. Plumbed, not built.
                    ctx.journal().record(Category.BRAIN, "chop", "replant deferred (placer not built)");
                }
                phase = Phase.COLLECT;
            }
            default -> throw new IllegalStateException("no advance from " + phase);
        }
        return TaskStatus.RUNNING;
    }

    private TaskStatus startStump(BrainContext ctx) {
        queue.addAll(tree.base()); // breaking the block under her own feet is allowed — she
        phase = Phase.STUMP;       // drops one block, exactly like a player would
        if (tree.base().isEmpty()) {
            return advancePhase(ctx); // floating remnant: straight to the summary + collect
        }
        return TaskStatus.RUNNING;
    }

    private TaskStatus finish(BrainContext ctx) {
        if (felled > 0) {
            ctx.knowledge().forget(PoiKind.TREE, memory.anchor());
            return TaskStatus.SUCCESS;
        }
        ctx.journal().record(Category.BRAIN, "chop", "nothing reachable to fell");
        return TaskStatus.FAILED; // real tree, kept in memory; retry pacing is the caller's job
    }

    private TaskStatus ghost(BrainContext ctx) {
        Pos a = memory.anchor();
        ctx.knowledge().forget(PoiKind.TREE, a);
        ctx.journal().record(Category.BRAIN, "chop",
                "grove gone — forgot TREE (" + a.x() + ", " + a.y() + ", " + a.z() + ")");
        return TaskStatus.FAILED;
    }

    // --- helpers ---------------------------------------------------------------------------------

    /** Seed for the re-scan: the anchor, or a cell just above it (the anchor may be a chopped stump). */
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
