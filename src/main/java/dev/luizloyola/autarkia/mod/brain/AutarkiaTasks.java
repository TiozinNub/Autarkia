package dev.luizloyola.autarkia.mod.brain;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.luizloyola.anima.core.brain.knowledge.Region;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.mod.brain.TaskCodecs;
import dev.luizloyola.autarkia.core.board.GatheringErrand;
import dev.luizloyola.autarkia.core.board.KeepStocked;
import dev.luizloyola.autarkia.core.tree.ChopPlan;
import dev.luizloyola.autarkia.core.board.HaulingErrand;
import dev.luizloyola.autarkia.core.tree.ChopPlannedTree;
import dev.luizloyola.autarkia.core.tree.TreeShape;
import java.util.HashSet;
import java.util.List;
import java.util.TreeSet;

/**
 * How Autarkia's own tasks write themselves down.
 *
 * <p><b>The card cannot be recompiled from the tree it is halfway through felling.</b> A remnant
 * is a different shape, so a fresh survey produces a different plan, and the layer and move
 * indices point into the plan they were made for.
 *
 * <p>The in-flight survey scan is not carried: a scratch structure and a pure function of the
 * world at the anchor, so an interrupted chop scans again and reaches the same tree.
 */
public final class AutarkiaTasks {

    private AutarkiaTasks() {
    }

    private static final Codec<Pos> POS = RecordCodecBuilder.create(p -> p.group(
            Codec.INT.fieldOf("x").forGetter(Pos::x),
            Codec.INT.fieldOf("y").forGetter(Pos::y),
            Codec.INT.fieldOf("z").forGetter(Pos::z)
    ).apply(p, Pos::new));

    private static final Codec<List<Pos>> POS_LIST = POS.listOf();

    private static final Codec<Region> REGION = RecordCodecBuilder.create(r -> r.group(
            POS.fieldOf("min").forGetter(Region::min),
            POS.fieldOf("max").forGetter(Region::max)
    ).apply(r, Region::new));

    private static final Codec<TreeShape.Trunk> TRUNK = RecordCodecBuilder.create(t -> t.group(
            POS_LIST.fieldOf("base").forGetter(TreeShape.Trunk::base),
            POS_LIST.fieldOf("column").forGetter(TreeShape.Trunk::column),
            POS_LIST.fieldOf("branches").forGetter(TreeShape.Trunk::branches),
            POS_LIST.fieldOf("leaves").forGetter(TreeShape.Trunk::leaves)
    ).apply(t, TreeShape.Trunk::new));

    private static final Codec<ChopPlan.Move> MOVE = RecordCodecBuilder.create(m -> m.group(
            POS.fieldOf("target").forGetter(ChopPlan.Move::target),
            POS.fieldOf("stand").forGetter(ChopPlan.Move::stand),
            POS_LIST.fieldOf("digs").forGetter(ChopPlan.Move::digs),
            Codec.BOOL.fieldOf("leap").forGetter(ChopPlan.Move::leap),
            Codec.BOOL.fieldOf("boost").forGetter(ChopPlan.Move::boost)
    ).apply(m, ChopPlan.Move::new));

    private static final Codec<ChopPlan.Layer> LAYER = RecordCodecBuilder.create(l -> l.group(
            Codec.INT.fieldOf("y").forGetter(ChopPlan.Layer::y),
            MOVE.listOf().fieldOf("moves").forGetter(ChopPlan.Layer::moves)
    ).apply(l, ChopPlan.Layer::new));

    /** Refusals round-trip by name; an unknown reason means a build that renamed one. */
    private static final Codec<ChopPlan.Reason> REASON = Codec.STRING.comapFlatMap(
            name -> {
                try {
                    return DataResult.success(ChopPlan.Reason.valueOf(name));
                } catch (IllegalArgumentException e) {
                    return DataResult.error(() -> "no refusal reason called \"" + name + "\"");
                }
            },
            ChopPlan.Reason::name);

    private static final Codec<ChopPlan.Refusal> REFUSAL = RecordCodecBuilder.create(r -> r.group(
            POS.fieldOf("cell").forGetter(ChopPlan.Refusal::cell),
            REASON.fieldOf("reason").forGetter(ChopPlan.Refusal::reason)
    ).apply(r, ChopPlan.Refusal::new));

    private static final Codec<ChopPlan> PLAN = RecordCodecBuilder.create(c -> c.group(
            POS.fieldOf("entry").forGetter(ChopPlan::entry),
            POS_LIST.fieldOf("mast").forGetter(ChopPlan::mast),
            LAYER.listOf().fieldOf("layers").forGetter(ChopPlan::layers),
            REFUSAL.listOf().fieldOf("refusals").forGetter(ChopPlan::refusals)
    ).apply(c, ChopPlan::new));

    private static final Codec<ChopPlannedTree.Progress> PROGRESS =
            RecordCodecBuilder.create(p -> p.group(
                    Codec.STRING.fieldOf("phase").forGetter(ChopPlannedTree.Progress::phase),
                    Codec.BOOL.fieldOf("walking").forGetter(ChopPlannedTree.Progress::walkIssued),
                    Codec.INT.fieldOf("walkTicks").forGetter(ChopPlannedTree.Progress::walkTicks),
                    Codec.BOOL.fieldOf("breaking").forGetter(ChopPlannedTree.Progress::breaking),
                    Codec.INT.fieldOf("pickupWait").forGetter(ChopPlannedTree.Progress::pickupWait),
                    Codec.INT.fieldOf("gatherWalks").forGetter(ChopPlannedTree.Progress::gatherWalks),
                    Codec.INT.fieldOf("decayWait").forGetter(ChopPlannedTree.Progress::decayWait),
                    Codec.INT.fieldOf("layerGatherWalks")
                            .forGetter(ChopPlannedTree.Progress::layerGatherWalks),
                    POS.optionalFieldOf("chasing").forGetter(
                            s -> java.util.Optional.ofNullable(s.chasing())),
                    POS.optionalFieldOf("lastSpot").forGetter(
                            s -> java.util.Optional.ofNullable(s.lastSpot())),
                    Codec.LONG.fieldOf("restingSince")
                            .forGetter(ChopPlannedTree.Progress::restingSince),
                    POS_LIST.fieldOf("unreachable")
                            .forGetter(ChopPlannedTree.Progress::unreachableDrops)
            ).apply(p, (phase, walking, walkTicks, breaking, pickupWait, gatherWalks, decayWait,
                        layerGatherWalks, chasing, lastSpot, restingSince, unreachable) ->
                    new ChopPlannedTree.Progress(phase, walking, walkTicks, breaking, pickupWait,
                            gatherWalks, decayWait, layerGatherWalks, chasing.orElse(null),
                            lastSpot.orElse(null), restingSince, unreachable)));

    private static final Codec<ChopPlannedTree.Site> SITE = RecordCodecBuilder.create(s -> s.group(
            Codec.INT.fieldOf("siteX").forGetter(ChopPlannedTree.Site::siteX),
            Codec.INT.fieldOf("siteZ").forGetter(ChopPlannedTree.Site::siteZ),
            Codec.INT.fieldOf("doorsteps").forGetter(ChopPlannedTree.Site::doorstepsTried),
            Codec.BOOL.fieldOf("axisFallback").forGetter(ChopPlannedTree.Site::axisFallback),
            Codec.BOOL.fieldOf("boostUp").forGetter(ChopPlannedTree.Site::boostUp),
            POS.optionalFieldOf("boostCell").forGetter(
                    site -> java.util.Optional.ofNullable(site.boostCell())),
            Codec.BOOL.fieldOf("riseForBoost").forGetter(ChopPlannedTree.Site::riseForBoost),
            Codec.BOOL.fieldOf("riseIssued").forGetter(ChopPlannedTree.Site::riseIssued)
    ).apply(s, (siteX, siteZ, doorsteps, axisFallback, boostUp, boostCell, riseForBoost,
                riseIssued) -> new ChopPlannedTree.Site(siteX, siteZ, doorsteps, axisFallback,
                    boostUp, boostCell.orElse(null), riseForBoost, riseIssued)));

    private static final Codec<ChopPlannedTree.Card> CARD = RecordCodecBuilder.create(c -> c.group(
            TRUNK.optionalFieldOf("tree").forGetter(
                    card -> java.util.Optional.ofNullable(card.tree())),
            PLAN.optionalFieldOf("plan").forGetter(
                    card -> java.util.Optional.ofNullable(card.plan())),
            POS_LIST.fieldOf("mastAhead").forGetter(ChopPlannedTree.Card::mastAhead),
            POS_LIST.fieldOf("digsAhead").forGetter(ChopPlannedTree.Card::digsAhead),
            POS_LIST.fieldOf("treeBlocks").forGetter(ChopPlannedTree.Card::treeBlocks),
            REGION.optionalFieldOf("claimed").forGetter(
                    card -> java.util.Optional.ofNullable(card.claimedArea())),
            Codec.INT.fieldOf("layer").forGetter(ChopPlannedTree.Card::layerIndex),
            Codec.INT.fieldOf("move").forGetter(ChopPlannedTree.Card::moveIndex)
    ).apply(c, (tree, plan, mastAhead, digsAhead, treeBlocks, claimed, layer, move) ->
            new ChopPlannedTree.Card(tree.orElse(null), plan.orElse(null), mastAhead, digsAhead,
                    treeBlocks, claimed.orElse(null), layer, move)));

    private static final Codec<ChopPlannedTree.Ending> ENDING =
            RecordCodecBuilder.create(e -> e.group(
                    POS_LIST.fieldOf("leftovers").forGetter(ChopPlannedTree.Ending::leftovers),
                    Codec.STRING.optionalFieldOf("ending").forGetter(
                            end -> java.util.Optional.ofNullable(end.ending())),
                    Codec.BOOL.fieldOf("bailing").forGetter(ChopPlannedTree.Ending::bailing),
                    Codec.STRING.optionalFieldOf("bailReason").forGetter(
                            end -> java.util.Optional.ofNullable(end.bailReason()))
            ).apply(e, (leftovers, ending, bailing, bailReason) -> new ChopPlannedTree.Ending(
                    leftovers, ending.orElse(null), bailing, bailReason.orElse(null))));

    /** The whole of a chop, as data. Built separately from the task codec below so the compiler
     *  has one thing to infer at a time. */
    private static final MapCodec<ChopPlannedTree.State> STATE =
            RecordCodecBuilder.mapCodec(t -> t.group(
                    POS.fieldOf("anchor").forGetter(ChopPlannedTree.State::anchor),
                    PROGRESS.fieldOf("progress").forGetter(ChopPlannedTree.State::progress),
                    SITE.fieldOf("site").forGetter(ChopPlannedTree.State::site),
                    CARD.fieldOf("card").forGetter(ChopPlannedTree.State::card),
                    ENDING.fieldOf("ending").forGetter(ChopPlannedTree.State::ending)
            ).apply(t, ChopPlannedTree.State::new));

    /** Call once from mod init, before anything can load a plan. */
    public static void install() {
        TaskCodecs.register("autarkia:chop", ChopPlannedTree.class,
                STATE.xmap(state -> new ChopPlannedTree(state.anchor()).restore(state),
                        ChopPlannedTree::snapshot));
        // A wrapper carries a TASK, so it leans on the dispatch codec the same way anima:try does;
        // the per-key lookup happens at parse time, which is what makes the recursion legal.
        //
        // REGISTERED THE DAY IT WAS WRITTEN, and it must be: an unregistered task does not fail
        // loudly on save, it NPEs out of a JSON primitive and takes the SERVER down with it
        // (docs/BUGS.md). A settler with one of these in her plan crashed a world on 2026-08-20
        // between felling a tree and the next autosave.
        TaskCodecs.register("autarkia:haul_errand", HaulingErrand.class,
                RecordCodecBuilder.mapCodec(t -> t.group(
                        TaskCodecs.codec().fieldOf("work").forGetter(HaulingErrand::work),
                        POS.fieldOf("yard").forGetter(HaulingErrand::yard),
                        Codec.INT.fieldOf("haul_line").forGetter(HaulingErrand::haulLine)
                ).apply(t, HaulingErrand::new)));
        // A gather's whole trip. Same rule as the wrapper above: registered the day it was
        // written, because an unregistered task takes the server down at the next autosave.
        TaskCodecs.register("autarkia:gather_errand", GatheringErrand.class,
                RecordCodecBuilder.mapCodec(t -> t.group(
                        ITEM_SPEC.fieldOf("spec").forGetter(GatheringErrand::spec),
                        Codec.INT.fieldOf("count").forGetter(GatheringErrand::count),
                        POS.fieldOf("yard").forGetter(GatheringErrand::yard)
                ).apply(t, GatheringErrand::new)));
    }

    /**
     * A class of items, in the two shapes a spec can have — the same fork {@code AnimaTasks} uses,
     * and no longer narrower than it. A mod-declared spec's matcher is a lambda and cannot be
     * written down, so its NAME is the handle and an unregistered one errors rather than inventing
     * a spec that matches nothing. A {@link ItemSpec#anyOf literal} spec has no declarer to put its
     * name back at boot, so its CONTENT is the handle, re-canonicalised through {@code anyOf} on
     * load. A gather is NOT always posted against a declared spec:
     * {@code board post gather <item>} builds a literal one, and the member walking it is holding
     * it when the world saves.
     */
    private static final Codec<ItemSpec> ITEM_SPEC =
            Codec.either(Codec.STRING, Codec.STRING.listOf())
                    .comapFlatMap(AutarkiaTasks::specFromEither, AutarkiaTasks::specToEither);

    private static DataResult<ItemSpec> specFromEither(Either<String, List<String>> written) {
        return written.map(
                name -> ItemSpec.byName(name)
                        .map(DataResult::success)
                        .orElseGet(() -> DataResult.error(
                                () -> "no item spec is registered as \"" + name + "\"")),
                ids -> ids.isEmpty()
                        ? DataResult.error(() -> "an item spec with no ids")
                        : DataResult.success(ItemSpec.anyOf(new HashSet<>(ids))));
    }

    private static Either<String, List<String>> specToEither(ItemSpec spec) {
        return ItemSpec.literalIds(spec)
                .<Either<String, List<String>>>map(ids -> Either.right(List.copyOf(new TreeSet<>(ids))))
                .orElseGet(() -> Either.left(spec.name()));
    }

    /**
     * A personal board's projects — the whole of layer 3 that lives on a body.
     *
     * <p>Autarkia's, not Anima's: a pack of pets is a party but never composes a board. Party
     * boards keep their own state in a server-scoped store instead.
     */
    public static final Codec<List<KeepStocked.State>> PERSONAL_BOARD =
            RecordCodecBuilder.<KeepStocked.State>create(k -> k.group(
                    Codec.INT.fieldOf("cooldown").forGetter(KeepStocked.State::cooldown),
                    Codec.INT.fieldOf("clock").forGetter(KeepStocked.State::clock),
                    Codec.INT.fieldOf("beats").forGetter(KeepStocked.State::beats),
                    Codec.BOOL.fieldOf("wanting").forGetter(KeepStocked.State::wanting),
                    Codec.BOOL.fieldOf("claimed").forGetter(KeepStocked.State::claimed)
            ).apply(k, KeepStocked.State::new)).listOf();
}
