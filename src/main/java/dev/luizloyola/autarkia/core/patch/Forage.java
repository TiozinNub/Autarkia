package dev.luizloyola.autarkia.core.patch;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.gate.Act;
import dev.luizloyola.anima.core.brain.gate.Acts;
import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.brain.knowledge.PoiMemory;
import dev.luizloyola.anima.core.brain.knowledge.Region;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.EnsureTable;
import dev.luizloyola.anima.core.brain.task.GoTo;
import dev.luizloyola.anima.core.brain.task.Method;
import dev.luizloyola.anima.core.brain.task.Task;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.autarkia.core.tree.TreeShape;
import java.util.List;
import java.util.Optional;

/**
 * Where ready food comes from before anybody farms: a remembered patch of berries or melons.
 * Registered under {@code ReadyFood.SPEC} and {@code Food.SPEC}, so a meal and a gather for HOME's
 * stores both reach it through {@code ObtainItem}.
 *
 * <p>A trip goes to the nearest patch that yields what is wanted, that nobody else is working and
 * this body has not been to lately, and picks what is ripe there — {@link PickPatch} looks on
 * arrival, not from here. Every trip rests the patch for {@link #REST_TICKS}: what was ripe has been
 * picked, and a patch found bare would otherwise be walked to again every round. Priced at the walk
 * plus {@link #WORK}, so food in hand or in a store wins wherever there is any.
 */
public final class Forage implements Method {

    /** Foraging is gated on doing, like every act; the Wood Age opens it. */
    public static final Act ACT = Acts.register(new Act("autarkia:forage"));

    /** Ticks a visited patch is left alone — two minutes, about as long as a picked bush takes. */
    static final long REST_TICKS = 2400;

    /** Walk-blocks the picking is worth on top of the walk. */
    static final double WORK = 8.0;

    /** What working each kind of patch yields — how a wanted spec picks the patches that are ways. */
    private record Yield(PoiKind kind, String itemId) {
    }

    private static final List<Yield> YIELDS = List.of(
            new Yield(Patches.BERRIES, "minecraft:sweet_berries"),
            new Yield(Patches.MELONS, "minecraft:melon_slice"));

    /** Whether some patch yields {@code itemId} — what forage can ever make. */
    public static boolean yields(String itemId) {
        return YIELDS.stream().anyMatch(yield -> yield.itemId().equals(itemId));
    }

    private final ItemSpec wanted;

    public Forage(ItemSpec wanted) {
        this.wanted = wanted;
    }

    @Override
    public boolean applicable(BrainContext ctx) {
        return nearestPatch(ctx).isPresent() && ctx.gate().mayDo(ACT);
    }

    @Override
    public double estimateCost(BrainContext ctx) {
        return nearestPatch(ctx)
                .map(patch -> Math.sqrt(TreeShape.horizontalDistSq(patch.anchor(),
                        ctx.percepts().position())) + WORK)
                .orElse(Double.POSITIVE_INFINITY);
    }

    @Override
    public List<Task> decompose(BrainContext ctx) {
        PoiMemory patch = nearestPatch(ctx).orElseThrow();
        long now = ctx.percepts().time();
        ctx.knowledge().avoid(patch.kind(), patch.anchor(), now + REST_TICKS);
        ctx.claims().claim(patch.kind(), patch.anchor(), Region.of(patch.anchor()), now);
        Pos beside = EnsureTable.WalkToKnown.standableBeside(patch.anchor(), ctx);
        return List.of(new GoTo(beside.x(), beside.y(), beside.z()),
                new PickPatch(patch.kind(), patch.anchor()));
    }

    @Override
    public String describe() {
        return "forage for " + wanted.name();
    }

    /** The nearest patch yielding {@code wanted}, not rested and nobody else's to work. */
    private Optional<PoiMemory> nearestPatch(BrainContext ctx) {
        Pos here = ctx.percepts().position();
        long now = ctx.percepts().time();
        PoiMemory best = null;
        long bestDist = Long.MAX_VALUE;
        for (Yield yield : YIELDS) {
            if (!wanted.matches(yield.itemId())) {
                continue;
            }
            for (PoiMemory patch : ctx.knowledge().all(yield.kind())) {
                if (ctx.knowledge().isAvoided(yield.kind(), patch.anchor(), now)
                        || !ctx.claims().availableTo(yield.kind(), patch.anchor(), now)) {
                    continue;
                }
                long dist = TreeShape.horizontalDistSq(patch.anchor(), here);
                if (dist < bestDist) {
                    bestDist = dist;
                    best = patch;
                }
            }
        }
        return Optional.ofNullable(best);
    }
}
