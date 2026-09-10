package dev.luizloyola.autarkia.mod.brain;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.mod.brain.TaskCodecs;
import dev.luizloyola.autarkia.core.board.GatheringErrand;
import dev.luizloyola.autarkia.core.board.KeepStocked;
import dev.luizloyola.autarkia.core.board.HaulingErrand;
import dev.luizloyola.autarkia.core.tree.Climb;
import dev.luizloyola.autarkia.core.tree.FellTree;
import java.util.HashSet;
import java.util.List;
import java.util.TreeSet;

/**
 * How Autarkia's own tasks write themselves down.
 *
 * <p>The chop writes its stage, the side it took and its {@link Climb}: the world holds every
 * other kind of progress, but a plan cannot be re-read off a trunk once the trunk is opened, and
 * a body mid-climb put back at the approach would walk out of the tree to walk back in.
 */
public final class AutarkiaTasks {

    private AutarkiaTasks() {
    }

    private static final Codec<Pos> POS = RecordCodecBuilder.create(p -> p.group(
            Codec.INT.fieldOf("x").forGetter(Pos::x),
            Codec.INT.fieldOf("y").forGetter(Pos::y),
            Codec.INT.fieldOf("z").forGetter(Pos::z)
    ).apply(p, Pos::new));

    private static final Codec<Climb> CLIMB = RecordCodecBuilder.create(c -> c.group(
            POS.fieldOf("stand").forGetter(Climb::stand),
            Codec.INT.fieldOf("need_feet_y").forGetter(Climb::needFeetY),
            Codec.BOOL.fieldOf("steps_in").forGetter(Climb::stepsIn),
            Codec.BOOL.optionalFieldOf("digs_in", false).forGetter(Climb::digsIn),
            POS.listOf().fieldOf("step_in").forGetter(Climb::stepIn),
            // The three lists are optional so a save from the plan's earlier shape still reads;
            // every stage after the plan reads the column afresh, so an empty one costs nothing.
            POS.listOf().optionalFieldOf("above", List.of()).forGetter(Climb::above),
            POS.listOf().optionalFieldOf("under", List.of()).forGetter(Climb::under),
            POS.listOf().optionalFieldOf("last", List.of()).forGetter(Climb::last),
            Codec.BOOL.fieldOf("complete").forGetter(Climb::complete),
            POS.listOf().optionalFieldOf("columns", List.of()).forGetter(Climb::columns),
            Codec.BOOL.optionalFieldOf("clockwise", false).forGetter(Climb::clockwise)
    ).apply(c, Climb::new));

    /** By name, guarded into a DataResult — never trusted raw off a hand-edited save. */
    private static final Codec<FellTree.Stage> STAGE = Codec.STRING.comapFlatMap(name -> {
        try {
            return DataResult.success(FellTree.Stage.valueOf(name));
        } catch (IllegalArgumentException unknown) {
            return DataResult.error(() -> "no chop stage is named \"" + name + "\"");
        }
    }, FellTree.Stage::name);

    /** Call once from mod init, before anything can load a plan. */
    public static void install() {
        // A task with no codec does not fail loudly on save, it NPEs out of a JSON primitive and
        // takes the server down. The guard in AutarkiaTaskCodecsTest catches the omission.
        // The stage, side and plan are optional so a save from before they existed still reads.
        TaskCodecs.register("autarkia:fell_tree", FellTree.class,
                RecordCodecBuilder.mapCodec(t -> t.group(
                        POS.fieldOf("anchor").forGetter(FellTree::anchor),
                        STAGE.optionalFieldOf("stage", FellTree.Stage.APPROACH)
                                .forGetter(FellTree::stage),
                        POS.optionalFieldOf("chosen").forGetter(FellTree::chosen),
                        CLIMB.optionalFieldOf("climb").forGetter(FellTree::climb)
                ).apply(t, FellTree::restored)));
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
