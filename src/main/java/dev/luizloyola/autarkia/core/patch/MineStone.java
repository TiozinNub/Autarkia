package dev.luizloyola.autarkia.core.patch;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.gate.Act;
import dev.luizloyola.anima.core.brain.gate.Acts;
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
 * Registered under {@link Stock#FURNACE_STONE}; in the Wood Age the gate lets it be reached only
 * inside the furnace's craft chain. A pickaxe comes first when none is carried, since stone mined bare-handed
 * drops nothing. Every trip rests the patch, as foraging does; one that gave nothing a furnace takes
 * — granite, tuff — is rested for a day ({@link MinePatch}).
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
    private Optional<PoiMemory> chosen = Optional.empty();

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
                .map(patch -> Math.sqrt(TreeShape.horizontalDistSq(patch.anchor(), ctx.percepts().position())) + WORK)
                .orElse(Double.POSITIVE_INFINITY);
    }

    @Override
    public List<Task> decompose(BrainContext ctx) {
        PoiMemory patch = nearestPatch(ctx).orElseThrow();
        ctx.knowledge().avoid(Landmarks.STONE_POI, patch.anchor(), ctx.percepts().time() + REST_TICKS);
        chosenAt = Long.MIN_VALUE;
        List<Task> steps = new ArrayList<>();
        if (ctx.percepts().inventory().count(Stock.PICKAXES.matcher()) == 0) {
            steps.add(new ObtainItem(Stock.PICKAXES, 1));
        }
        Pos beside = EnsureTable.WalkToKnown.standableBeside(patch.anchor(), ctx);
        steps.add(new GoTo(beside.x(), beside.y(), beside.z()));
        steps.add(new MinePatch(patch.anchor(), patch.bounds(), wanted));
        return steps;
    }

    @Override
    public String describe() {
        return "mine stone for " + wanted.name();
    }

    /** The nearest unrested patch worth the walk; asked three times a choice, so read once a tick. */
    private Optional<PoiMemory> nearestPatch(BrainContext ctx) {
        Pos here = ctx.percepts().position();
        long now = ctx.percepts().time();
        if (now == chosenAt && here.equals(chosenFrom)) {
            return chosen;
        }
        List<PoiMemory> open = new ArrayList<>();
        for (PoiMemory patch : ctx.knowledge().all(Landmarks.STONE_POI)) {
            if (!ctx.knowledge().isAvoided(Landmarks.STONE_POI, patch.anchor(), now)) {
                open.add(patch);
            }
        }
        open.sort(java.util.Comparator.comparingLong(patch -> TreeShape.horizontalDistSq(patch.anchor(), here)));
        BlockProbe probe = ctx.percepts().blocks();
        chosen = open.stream().filter(patch -> worthMining(probe, patch, here)).findFirst();
        chosenAt = now;
        chosenFrom = here;
        return chosen;
    }

    /**
     * Whether some of the patch stands above the ground round it. One out of sight cannot be judged
     * from here and is judged on arrival; one in sight with nothing standing is passed by, so flat
     * stone is never quarried.
     */
    static boolean worthMining(BlockProbe probe, PoiMemory patch, Pos here) {
        return probe.groundY(patch.anchor().x(), patch.anchor().z()) == Integer.MIN_VALUE
                || !MinePatch.exposed(probe, patch.bounds(), here).isEmpty();
    }
}
