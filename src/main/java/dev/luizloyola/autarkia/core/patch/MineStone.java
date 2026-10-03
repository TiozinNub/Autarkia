package dev.luizloyola.autarkia.core.patch;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.gate.Act;
import dev.luizloyola.anima.core.brain.gate.Acts;
import dev.luizloyola.anima.core.brain.knowledge.AgentKnowledge;
import dev.luizloyola.anima.core.brain.knowledge.BlockProbe;
import dev.luizloyola.anima.core.brain.knowledge.PoiMemory;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.EnsureTable;
import dev.luizloyola.anima.core.brain.task.GoTo;
import dev.luizloyola.anima.core.brain.task.Method;
import dev.luizloyola.anima.core.brain.task.ObtainItem;
import dev.luizloyola.anima.core.brain.task.Task;
import dev.luizloyola.anima.core.continuity.Ephemeral;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.autarkia.core.board.Stock;
import dev.luizloyola.autarkia.core.tree.TreeShape;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Stone from where it shows at the surface (directions spec, decision 19): the nearest remembered
 * patch of exposed stone standing above the ground round it, mined from the top with a pickaxe.
 * With none standing, the most worthwhile flat patch gives its top layer instead ({@link
 * MinePatch#layer}): a furnace waits on eight stone, and a forest may show no other.
 * Registered under {@link Stock#FURNACE_STONE}; in the Wood Age the gate lets it be reached only
 * inside the furnace's craft chain. A pickaxe comes first when none is carried, since stone mined bare-handed
 * drops nothing. Every trip rests the patch, as foraging does, but a rested patch in sight with
 * stone still standing may be mined again (Luiz, 2026-10-02): otherwise a far trip to a lone patch
 * comes home with 8. One that gave nothing a furnace takes — granite, tuff — is rested for a day
 * ({@link MinePatch}).
 */
public final class MineStone implements Method {

    /** Digging stone is gated on doing; the Wood Age opens it. */
    public static final Act ACT = Acts.register(new Act("autarkia:dig_stone"));

    static final long REST_TICKS = 1200;

    /** Walk-blocks the mining is worth on top of the walk. */
    static final double WORK = 12.0;

    private final ItemSpec wanted;
    @Ephemeral("a memo of this tick's choice, made again when a way is next chosen")
    private long chosenAt = Long.MIN_VALUE;
    @Ephemeral("goes with the memo")
    private Pos chosenFrom;
    @Ephemeral("goes with the memo")
    private Optional<Choice> chosen = Optional.empty();

    /** A patch to go to, and whether its top layer may be taken when nothing there stands. */
    private record Choice(PoiMemory patch, boolean lastResort) {
    }

    public MineStone(ItemSpec wanted) {
        this.wanted = wanted;
    }

    @Override
    public boolean applicable(BrainContext ctx) {
        return ctx.gate().mayDo(ACT) && nearestPatch(ctx).isPresent();
    }

    @Override
    public double estimateCost(BrainContext ctx) {
        return nearestPatch(ctx)
                .map(choice -> Math.sqrt(TreeShape.horizontalDistSq(choice.patch().anchor(),
                        ctx.percepts().position())) + WORK)
                .orElse(Double.POSITIVE_INFINITY);
    }

    @Override
    public List<Task> decompose(BrainContext ctx) {
        Choice choice = nearestPatch(ctx).orElseThrow();
        PoiMemory patch = choice.patch();
        ctx.knowledge().rest(Landmarks.STONE_POI, patch.anchor(), ctx.percepts().time() + REST_TICKS);
        chosenAt = Long.MIN_VALUE;
        List<Task> steps = new ArrayList<>();
        if (ctx.percepts().inventory().count(Stock.PICKAXES.matcher()) == 0) {
            steps.add(new ObtainItem(Stock.PICKAXES, 1));
        }
        Pos beside = EnsureTable.WalkToKnown.standableBeside(patch.anchor(), ctx);
        steps.add(new GoTo(beside.x(), beside.y(), beside.z()));
        steps.add(new MinePatch(patch.anchor(), patch.bounds(), wanted, choice.lastResort()));
        return steps;
    }

    @Override
    public String describe() {
        return "mine stone for " + wanted.name();
    }

    /**
     * The nearest unrested patch worth the walk, else the last resort; asked three times a choice,
     * so read once a tick.
     */
    private Optional<Choice> nearestPatch(BrainContext ctx) {
        Pos here = ctx.percepts().position();
        long now = ctx.percepts().time();
        if (now == chosenAt && here.equals(chosenFrom)) {
            return chosen;
        }
        List<PoiMemory> open = new ArrayList<>();
        java.util.Set<Pos> resting = new java.util.HashSet<>();
        java.util.Map<Pos, AgentKnowledge.Avoid> marks = ctx.knowledge().avoids(Landmarks.STONE_POI);
        for (PoiMemory patch : ctx.knowledge().all(Landmarks.STONE_POI)) {
            AgentKnowledge.Avoid mark = marks.get(patch.anchor());
            if (mark == null || mark.until() <= now) {
                open.add(patch);
            } else if (mark.strikes() == 0) {
                open.add(patch);
                resting.add(patch.anchor());
            }
        }
        open.sort(java.util.Comparator.comparingLong(patch -> TreeShape.horizontalDistSq(patch.anchor(), here)));
        BlockProbe probe = ctx.percepts().blocks();
        PoiMemory worth = null;
        boolean standing = false;
        for (PoiMemory patch : open) {
            boolean inSight = probe.groundY(patch.anchor().x(), patch.anchor().z()) != Integer.MIN_VALUE;
            boolean stands = inSight && !MinePatch.exposed(probe, patch.bounds(), here).isEmpty();
            standing |= stands;
            if (worth == null && (stands || (!inSight && !resting.contains(patch.anchor())))) {
                worth = patch;
            }
            if (worth != null && standing) {
                break;
            }
        }
        // A patch out of sight cannot be judged from here and is judged on arrival, its layer taken
        // if it is flat and no patch in sight has stone standing.
        chosen = worth != null ? Optional.of(new Choice(worth, !standing))
                : lastResort(probe, open.stream().filter(patch -> !resting.contains(patch.anchor())).toList(), here)
                        .map(patch -> new Choice(patch, true));
        chosenAt = now;
        chosenFrom = here;
        return chosen;
    }

    /**
     * With no patch standing above the ground round it, the one whose top layer costs the least walk
     * a block: near, and with stone to spare.
     */
    private static Optional<PoiMemory> lastResort(BlockProbe probe, List<PoiMemory> open, Pos here) {
        PoiMemory best = null;
        double bestWalk = Double.POSITIVE_INFINITY;
        for (PoiMemory patch : open) {
            int cells = MinePatch.layer(probe, patch.bounds(), here).size();
            double walk = Math.sqrt(TreeShape.horizontalDistSq(patch.anchor(), here)) / Math.max(cells, 1);
            if (cells > 0 && walk < bestWalk) {
                best = patch;
                bestWalk = walk;
            }
        }
        return Optional.ofNullable(best);
    }
}
