package dev.luizloyola.autarkia.mod.board;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.knowledge.CoverageGrid;
import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.brain.knowledge.Region;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.core.social.PartyId;
import dev.luizloyola.autarkia.core.board.Board;
import dev.luizloyola.autarkia.core.board.ClearPlants;
import dev.luizloyola.autarkia.core.board.FellTrees;
import dev.luizloyola.autarkia.core.board.Explore;
import dev.luizloyola.autarkia.core.board.Flatten;
import dev.luizloyola.autarkia.core.board.Gather;
import dev.luizloyola.autarkia.core.board.PartyBoard;
import dev.luizloyola.autarkia.core.board.ProjectState;
import dev.luizloyola.autarkia.core.board.SetUp;
import dev.luizloyola.autarkia.core.board.WorkKey;
import dev.luizloyola.autarkia.core.direction.HomeJudge;
import dev.luizloyola.autarkia.core.direction.HomeSearch;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;

/**
 * How a party board writes itself down, kept apart from {@link PartyBoardData} so it round-trips
 * in a plain unit test — no server, no world, no save file.
 *
 * <p>Vanilla parses saved data with {@code resultOrPartial}, so a row shape that stops decoding
 * does not fail the load: the store comes back with fewer rows and the next autosave makes it
 * permanent. {@code StoreGuard} catches that at boot; this catches it before the build ships.
 */
public final class PartyBoardCodecs {

    private PartyBoardCodecs() {
    }

    public static final Codec<Pos> POS = BlockPos.CODEC.xmap(
            block -> new Pos(block.getX(), block.getY(), block.getZ()),
            pos -> new BlockPos(pos.x(), pos.y(), pos.z()));

    public static final Codec<Region> REGION = RecordCodecBuilder.create(region -> region.group(
            POS.fieldOf("min").forGetter(Region::min),
            POS.fieldOf("max").forGetter(Region::max)
    ).apply(region, Region::new));

    /**
     * Phases and target states travel by NAME, not by ordinal. An ordinal is a position in a list
     * somebody will reorder, and a save file is the one reader that cannot be recompiled with it.
     *
     * <p>Decoding is lenient because the phase names changed on 2026-08-23: {@code SURVEYING},
     * {@code CLEARING} and {@code VERIFYING} all mean {@code WORKING} now — see
     * {@link FellTrees#phaseByName}. Encoding still writes the real name.
     */
    public static final Codec<FellTrees.Phase> PHASE =
            Codec.STRING.xmap(FellTrees::phaseByName, Enum::name);

    public static final Codec<FellTrees.TargetState> TARGET_STATE =
            Codec.STRING.xmap(FellTrees.TargetState::valueOf, Enum::name);

    public static final Codec<FellTrees.CellMask> CELL_MASK =
            RecordCodecBuilder.create(cell -> cell.group(
                    POS.fieldOf("at").forGetter(FellTrees.CellMask::corner),
                    Codec.INT.fieldOf("mask").forGetter(FellTrees.CellMask::mask)
            ).apply(cell, FellTrees.CellMask::new));

    public static final Codec<FellTrees.Target> TARGET =
            RecordCodecBuilder.create(target -> target.group(
                    POS.fieldOf("at").forGetter(FellTrees.Target::anchor),
                    TARGET_STATE.fieldOf("state").forGetter(FellTrees.Target::state),
                    Codec.INT.optionalFieldOf("failures", 0).forGetter(FellTrees.Target::failures),
                    Codec.LONG.optionalFieldOf("retry", 0L).forGetter(FellTrees.Target::retryAfter),
                    UUIDUtil.CODEC.listOf().optionalFieldOf("failed_by", List.of())
                            .forGetter(t -> t.failedBy().stream().map(AgentId::value).toList())
            ).apply(target, (at, state, failures, retry, failedBy) -> new FellTrees.Target(
                    at, state, failures, retry, failedBy.stream().map(AgentId::of).toList())));

    public static final Codec<FellTrees.SliceCooldown> SLICE_COOLDOWN =
            RecordCodecBuilder.create(cooldown -> cooldown.group(
                    Codec.INT.fieldOf("slice").forGetter(FellTrees.SliceCooldown::slice),
                    Codec.LONG.fieldOf("retry").forGetter(FellTrees.SliceCooldown::retryAfter)
            ).apply(cooldown, FellTrees.SliceCooldown::new));

    /**
     * Everything a {@code fell_trees} row carries beyond its kind — a {@link MapCodec} rather than
     * the plain {@link Codec} this used to be, because {@link #PROJECT} below needs its fields flat
     * in the same object as {@code type}, not nested under a sub-key.
     */
    public static final MapCodec<FellTrees.State> FELL_TREES =
            RecordCodecBuilder.mapCodec(project -> project.group(
                    Codec.STRING.fieldOf("clearing").forGetter(FellTrees.State::clearing),
                    REGION.fieldOf("bounds").forGetter(FellTrees.State::bounds),
                    Codec.DOUBLE.fieldOf("priority").forGetter(FellTrees.State::priority),
                    PHASE.fieldOf("phase").forGetter(FellTrees.State::phase),
                    SLICE_COOLDOWN.listOf().optionalFieldOf("cooldowns", List.of())
                            .forGetter(FellTrees.State::sliceCooldowns),
                    TARGET.listOf().optionalFieldOf("targets", List.of())
                            .forGetter(FellTrees.State::targets),
                    Codec.INT.optionalFieldOf("felled_since_reopen", 0)
                            .forGetter(FellTrees.State::felledSinceReopen),
                    // The frontier itself. A short read here is a box re-swept from scratch, which
                    // is what StoreGuard's row count is for.
                    CELL_MASK.listOf().optionalFieldOf("covered", List.of())
                            .forGetter(FellTrees.State::covered),
                    // Pre-2026-08-23 saves listed whole settled corners under this name. Read them
                    // as fully covered cells so a live world survives the change rather than losing
                    // its sweep; never written, since a fresh save always has "covered" instead.
                    POS.listOf().optionalFieldOf("swept", List.of())
                            .forGetter(state -> List.<Pos>of()),
                    // Whether the wood goes home. A save from before 2026-10-01 named a yard instead;
                    // read as going home, never written.
                    Codec.BOOL.optionalFieldOf("home").forGetter(state -> java.util.Optional.of(state.home())),
                    POS.optionalFieldOf("yard").forGetter(state -> java.util.Optional.empty()),
                    // Who has worked the box, so the end of the job still knows whose loads to
                    // bring in after a restart. A save from before 2026-09-27 has no crew yet.
                    UUIDUtil.CODEC.listOf().optionalFieldOf("crew", List.of())
                            .forGetter(state -> state.crew().stream().map(AgentId::value).toList())
            ).apply(project, (clearing, bounds, priority, phase, cooldowns, targets, felled,
                    covered, legacySwept, home, legacyYard, crew) -> new FellTrees.State(
                            clearing, bounds, priority, phase, cooldowns, targets, felled,
                            covered.isEmpty()
                                    ? legacySwept.stream()
                                            .map(at -> new FellTrees.CellMask(at, CoverageGrid.FULL))
                                            .toList()
                                    : covered,
                            home.orElse(legacyYard.isPresent()), crew.stream().map(AgentId::of).toList())));

    /** What one home chest held when somebody last looked, and when they looked. */
    public static final Codec<Gather.Reading> READING =
            RecordCodecBuilder.create(reading -> reading.group(
                    POS.fieldOf("chest").forGetter(Gather.Reading::chest),
                    Codec.INT.fieldOf("count").forGetter(Gather.Reading::count),
                    // The tick a belief was formed. Without it a restart makes every reading look
                    // freshly taken, a stale reporter overwrites a newer one, and a disproved chest
                    // comes back as a phantom the project can close over.
                    Codec.LONG.fieldOf("seen").forGetter(Gather.Reading::at)
            ).apply(reading, Gather.Reading::new));

    /**
     * One member's outstanding trip. Written down because its SIZE is not derivable from the rest
     * of the row — see {@code Gather.Trip} for what a reload that re-derived it does to the holds
     * saved beside it.
     */
    public static final Codec<Gather.Trip> TRIP = RecordCodecBuilder.create(trip -> trip.group(
            UUIDUtil.CODEC.fieldOf("who").forGetter(out -> out.who().value()),
            Codec.INT.fieldOf("size").forGetter(Gather.Trip::size)
    ).apply(trip, (who, size) -> new Gather.Trip(AgentId.of(who), size)));

    /**
     * One member waiting out a failed trip — the same shape {@link #SLICE_COOLDOWN} gives a slice,
     * keyed by member instead of place because that is what {@link Gather} can name a failure by.
     */
    public static final Codec<Gather.Cooldown> GATHER_COOLDOWN =
            RecordCodecBuilder.create(cooldown -> cooldown.group(
                    UUIDUtil.CODEC.fieldOf("who").forGetter(out -> out.who().value()),
                    Codec.LONG.fieldOf("retry").forGetter(Gather.Cooldown::retryAfter)
            ).apply(cooldown, (who, retry) -> new Gather.Cooldown(AgentId.of(who), retry)));

    /**
     * The {@link ItemSpec} a gather is for, in the two shapes a spec can have — the fork
     * {@code AnimaTasks} writes plans with, and for the same reason. A mod-declared spec's matcher
     * is a lambda and cannot be written down, so its NAME is the handle and the bootstrap that
     * declares it puts it back. A {@link ItemSpec#anyOf literal} spec — what
     * {@code board post gather <item>} builds — has no declarer, so nothing re-registers its name
     * at boot and the name alone is a dead handle; its CONTENT travels instead, re-canonicalised
     * through {@code anyOf} on load, which is what puts the name back for {@link Gather#restore}.
     *
     * <p>An unrecognised NAME passes through rather than erroring, unlike the task codec's: this
     * row must reach {@code PartyBoardData.refuseUnknown}, which refuses to run a world holding a
     * project it cannot rebuild. Failing here instead would drop the row inside a party that still
     * decodes, and the store's guard counts parties, not projects.
     */
    public static final Codec<String> SPEC = Codec.either(Codec.STRING, Codec.STRING.listOf())
            .comapFlatMap(PartyBoardCodecs::specFromEither, PartyBoardCodecs::specToEither);

    private static DataResult<String> specFromEither(Either<String, List<String>> written) {
        return written.map(
                DataResult::success,
                ids -> ids.isEmpty()
                        ? DataResult.error(() -> "a gather for an item spec with no ids")
                        : DataResult.success(ItemSpec.anyOf(new HashSet<>(ids)).name()));
    }

    private static Either<String, List<String>> specToEither(String name) {
        return ItemSpec.byName(name).flatMap(ItemSpec::literalIds)
                .<Either<String, List<String>>>map(ids -> Either.right(List.copyOf(new TreeSet<>(ids))))
                .orElseGet(() -> Either.left(name));
    }

    /**
     * Everything a {@code gather} row carries beyond its kind. The split travels as a registry NAME
     * for the reason {@code AnimaTasks} gives: a policy is picked by id, not written down. The spec
     * travels as {@link #SPEC}.
     *
     * <p>The item OBJECTS are not written — they are exhaust — but who owes what is, beside the
     * {@link WorkKey.ForMember} on each hold. The two have to agree, and only one of them can be
     * re-derived.
     */
    public static final MapCodec<Gather.State> GATHER =
            RecordCodecBuilder.mapCodec(project -> project.group(
                    SPEC.fieldOf("spec").forGetter(Gather.State::spec),
                    Codec.INT.fieldOf("target").forGetter(Gather.State::target),
                    Codec.DOUBLE.fieldOf("priority").forGetter(Gather.State::priority),
                    UUIDUtil.CODEC.fieldOf("party").forGetter(state -> state.party().value()),
                    Codec.STRING.fieldOf("split").forGetter(Gather.State::split),
                    POS.listOf().optionalFieldOf("chests", List.of())
                            .forGetter(Gather.State::chests),
                    READING.listOf().optionalFieldOf("readings", List.of())
                            .forGetter(Gather.State::readings),
                    TRIP.listOf().optionalFieldOf("trips", List.of())
                            .forGetter(Gather.State::trips),
                    // A world saved before pacing existed carries none — read as nobody cooling,
                    // never as a decode failure that costs the row.
                    GATHER_COOLDOWN.listOf().optionalFieldOf("cooldowns", List.of())
                            .forGetter(Gather.State::cooldowns),
                    // The project's clock, since 2026-09-26; absent, the restore's tick stands in.
                    Codec.LONG.optionalFieldOf("last_tick", -1L).forGetter(Gather.State::lastTick)
            ).apply(project, (spec, target, priority, party, split, chests, readings, trips,
                    cooldowns, lastTick) -> new Gather.State(spec, target, priority,
                            PartyId.of(party), split, chests, readings, trips, cooldowns, lastTick)));

    /** A station by the place kind it is remembered as and the block that places it. */
    public static final Codec<SetUp.Station> STATION = RecordCodecBuilder.create(station -> station.group(
            Codec.STRING.comapFlatMap(
                    key -> PoiKind.byKey(key).map(DataResult::success)
                            .orElseGet(() -> DataResult.error(() -> "no place kind \"" + key + "\"")),
                    PoiKind::key).fieldOf("kind").forGetter(SetUp.Station::kind),
            Codec.STRING.fieldOf("item").forGetter(SetUp.Station::itemId)
    ).apply(station, SetUp.Station::new));

    /** Everything a {@code set_up} row carries beyond its kind. */
    public static final MapCodec<SetUp.State> SET_UP =
            RecordCodecBuilder.mapCodec(project -> project.group(
                    STATION.listOf().fieldOf("stations").forGetter(SetUp.State::stations),
                    POS.fieldOf("near").forGetter(SetUp.State::near),
                    Codec.DOUBLE.fieldOf("priority").forGetter(SetUp.State::priority),
                    Codec.INT.optionalFieldOf("next", 0).forGetter(SetUp.State::next),
                    GATHER_COOLDOWN.listOf().optionalFieldOf("cooldowns", List.of())
                            .forGetter(SetUp.State::cooldowns),
                    Codec.LONG.optionalFieldOf("last_tick", -1L).forGetter(SetUp.State::lastTick)
            ).apply(project, SetUp.State::new));

    /** Everything a {@code tend} row carries: where, what, who came first, and when it fell due. */
    public static final MapCodec<dev.luizloyola.autarkia.core.board.Tend.State> TEND =
            RecordCodecBuilder.mapCodec(project -> project.group(
                    POS.fieldOf("at").forGetter(dev.luizloyola.autarkia.core.board.Tend.State::at),
                    Codec.STRING.fieldOf("output").forGetter(dev.luizloyola.autarkia.core.board.Tend.State::output),
                    SPEC.optionalFieldOf("fuel").forGetter(dev.luizloyola.autarkia.core.board.Tend.State::fuel),
                    // Whether the output goes home; a save from before 2026-10-01 named a yard instead.
                    Codec.BOOL.optionalFieldOf("home").forGetter(state -> java.util.Optional.of(state.home())),
                    POS.optionalFieldOf("yard").forGetter(state -> java.util.Optional.empty()),
                    UUIDUtil.CODEC.fieldOf("starter").forGetter(state -> state.starter().value()),
                    Codec.LONG.fieldOf("due_at").forGetter(dev.luizloyola.autarkia.core.board.Tend.State::dueAt),
                    Codec.BOOL.optionalFieldOf("done", false).forGetter(dev.luizloyola.autarkia.core.board.Tend.State::done),
                    GATHER_COOLDOWN.listOf().optionalFieldOf("cooldowns", List.of())
                            .forGetter(dev.luizloyola.autarkia.core.board.Tend.State::cooldowns),
                    Codec.LONG.optionalFieldOf("last_tick", -1L).forGetter(dev.luizloyola.autarkia.core.board.Tend.State::lastTick)
            ).apply(project, (at, output, fuel, home, legacyYard, starter, dueAt, done, cooldowns, lastTick) ->
                    new dev.luizloyola.autarkia.core.board.Tend.State(at, output, fuel,
                            home.orElse(legacyYard.isPresent()), AgentId.of(starter),
                            dueAt, done, cooldowns, lastTick)));

    /** Everything a {@code fire} row carries: the furnace, the load and its fuel. */
    public static final MapCodec<dev.luizloyola.autarkia.core.board.Fire.State> FIRE =
            RecordCodecBuilder.mapCodec(project -> project.group(
                    POS.fieldOf("at").forGetter(dev.luizloyola.autarkia.core.board.Fire.State::at),
                    SPEC.fieldOf("input").forGetter(dev.luizloyola.autarkia.core.board.Fire.State::input),
                    Codec.INT.fieldOf("count").forGetter(dev.luizloyola.autarkia.core.board.Fire.State::count),
                    SPEC.fieldOf("fuel").forGetter(dev.luizloyola.autarkia.core.board.Fire.State::fuel),
                    Codec.DOUBLE.fieldOf("priority").forGetter(dev.luizloyola.autarkia.core.board.Fire.State::priority),
                    Codec.BOOL.optionalFieldOf("done", false).forGetter(dev.luizloyola.autarkia.core.board.Fire.State::done),
                    GATHER_COOLDOWN.listOf().optionalFieldOf("cooldowns", List.of())
                            .forGetter(dev.luizloyola.autarkia.core.board.Fire.State::cooldowns),
                    Codec.LONG.optionalFieldOf("last_tick", -1L).forGetter(dev.luizloyola.autarkia.core.board.Fire.State::lastTick)
            ).apply(project, dev.luizloyola.autarkia.core.board.Fire.State::new));

    /** Everything a {@code cook} row carries: the campfire and how much is to be cooked there. */
    public static final MapCodec<dev.luizloyola.autarkia.core.board.Cook.State> COOK =
            RecordCodecBuilder.mapCodec(project -> project.group(
                    POS.fieldOf("at").forGetter(dev.luizloyola.autarkia.core.board.Cook.State::at),
                    Codec.INT.fieldOf("count").forGetter(dev.luizloyola.autarkia.core.board.Cook.State::count),
                    Codec.DOUBLE.fieldOf("priority").forGetter(dev.luizloyola.autarkia.core.board.Cook.State::priority),
                    Codec.BOOL.optionalFieldOf("done", false).forGetter(dev.luizloyola.autarkia.core.board.Cook.State::done),
                    GATHER_COOLDOWN.listOf().optionalFieldOf("cooldowns", List.of())
                            .forGetter(dev.luizloyola.autarkia.core.board.Cook.State::cooldowns),
                    Codec.LONG.optionalFieldOf("last_tick", -1L).forGetter(dev.luizloyola.autarkia.core.board.Cook.State::lastTick)
            ).apply(project, dev.luizloyola.autarkia.core.board.Cook.State::new));

    /**
     * A flatten's columns packed six ints apiece — x, z, ground, goal, now, refused — since an area
     * of 64 a side is four thousand of them.
     */
    private static final Codec<List<Flatten.Col>> FLATTEN_COLS = Codec.INT_STREAM.comapFlatMap(
            stream -> {
                int[] ints = stream.toArray();
                if (ints.length % 6 != 0) {
                    return DataResult.error(() -> "flatten columns: " + ints.length + " ints, not sixes");
                }
                List<Flatten.Col> cols = new java.util.ArrayList<>(ints.length / 6);
                for (int i = 0; i < ints.length; i += 6) {
                    cols.add(new Flatten.Col(ints[i], ints[i + 1], ints[i + 2], ints[i + 3], ints[i + 4],
                            ints[i + 5] != 0));
                }
                return DataResult.success(cols);
            },
            cols -> java.util.stream.IntStream.of(cols.stream().flatMapToInt(c -> java.util.stream.IntStream.of(
                    c.x(), c.z(), c.ground(), c.goal(), c.now(), c.refused() ? 1 : 0)).toArray()));

    private static final Codec<Flatten.Cooldown> FLATTEN_COOLDOWN =
            RecordCodecBuilder.create(cooldown -> cooldown.group(
                    Codec.STRING.fieldOf("flavour").forGetter(Flatten.Cooldown::flavour),
                    POS.fieldOf("at").forGetter(Flatten.Cooldown::at),
                    Codec.INT.fieldOf("failures").forGetter(Flatten.Cooldown::failures),
                    Codec.LONG.fieldOf("retry_after").forGetter(Flatten.Cooldown::retryAfter)
            ).apply(cooldown, Flatten.Cooldown::new));

    /** Everything a {@code flatten} row carries: the plan's goals and how far each column has got. */
    public static final MapCodec<Flatten.State> FLATTEN =
            RecordCodecBuilder.mapCodec(project -> project.group(
                    REGION.fieldOf("area").forGetter(Flatten.State::area),
                    Codec.INT.fieldOf("tolerance").forGetter(Flatten.State::tolerance),
                    Codec.INT.fieldOf("y").forGetter(Flatten.State::y),
                    Codec.STRING.fieldOf("why").forGetter(Flatten.State::why),
                    Codec.DOUBLE.fieldOf("priority").forGetter(Flatten.State::priority),
                    FLATTEN_COLS.fieldOf("cols").forGetter(Flatten.State::cols),
                    FLATTEN_COOLDOWN.listOf().optionalFieldOf("cooldowns", List.of())
                            .forGetter(Flatten.State::cooldowns),
                    Codec.LONG.optionalFieldOf("material_wait_until", 0L)
                            .forGetter(Flatten.State::materialWaitUntil)
            ).apply(project, Flatten.State::new));
    private static final Codec<ClearPlants.Failure> PLANT_FAILURE = RecordCodecBuilder.create(failure ->
            failure.group(
                    Codec.INT.fieldOf("strip").forGetter(ClearPlants.Failure::strip),
                    UUIDUtil.CODEC.fieldOf("who").forGetter(f -> f.who().value())
            ).apply(failure, (strip, who) -> new ClearPlants.Failure(strip, AgentId.of(who))));

    /** Everything a {@code clear_plants} row carries: the box, and which strips are done or failed. */
    public static final MapCodec<ClearPlants.State> CLEAR_PLANTS =
            RecordCodecBuilder.mapCodec(project -> project.group(
                    REGION.fieldOf("bounds").forGetter(ClearPlants.State::bounds),
                    Codec.DOUBLE.fieldOf("priority").forGetter(ClearPlants.State::priority),
                    Codec.INT.listOf().optionalFieldOf("done", List.of()).forGetter(ClearPlants.State::done),
                    PLANT_FAILURE.listOf().optionalFieldOf("failures", List.of())
                            .forGetter(ClearPlants.State::failures),
                    GATHER_COOLDOWN.listOf().optionalFieldOf("cooldowns", List.of())
                            .forGetter(ClearPlants.State::cooldowns),
                    Codec.LONG.optionalFieldOf("last_tick", -1L).forGetter(ClearPlants.State::lastTick)
            ).apply(project, ClearPlants.State::new));

    private static final Codec<dev.luizloyola.autarkia.core.board.Deconstruct.Target> DECONSTRUCT_TARGET =
            RecordCodecBuilder.create(target -> target.group(
                    POS.fieldOf("at").forGetter(dev.luizloyola.autarkia.core.board.Deconstruct.Target::at),
                    Codec.STRING.fieldOf("item").forGetter(dev.luizloyola.autarkia.core.board.Deconstruct.Target::item),
                    Codec.BOOL.optionalFieldOf("container", false)
                            .forGetter(dev.luizloyola.autarkia.core.board.Deconstruct.Target::container)
            ).apply(target, dev.luizloyola.autarkia.core.board.Deconstruct.Target::new));

    /** A block's tally: its trips so far, and, once settled, whether it came down. */
    private record DeconstructTally(Pos at, int trips, Optional<Boolean> gone) {
    }

    private static final Codec<DeconstructTally> DECONSTRUCT_TALLY = RecordCodecBuilder.create(tally -> tally.group(
            POS.fieldOf("at").forGetter(DeconstructTally::at),
            Codec.INT.optionalFieldOf("trips", 0).forGetter(DeconstructTally::trips),
            Codec.BOOL.optionalFieldOf("gone").forGetter(DeconstructTally::gone)
    ).apply(tally, DeconstructTally::new));

    /** Everything a {@code deconstruct} row carries: the blocks, why, and how far each has got. */
    public static final MapCodec<dev.luizloyola.autarkia.core.board.Deconstruct.State> DECONSTRUCT =
            RecordCodecBuilder.mapCodec(project -> project.group(
                    DECONSTRUCT_TARGET.listOf().fieldOf("targets")
                            .forGetter(dev.luizloyola.autarkia.core.board.Deconstruct.State::targets),
                    Codec.STRING.optionalFieldOf("why", "").forGetter(dev.luizloyola.autarkia.core.board.Deconstruct.State::why),
                    Codec.DOUBLE.fieldOf("priority").forGetter(dev.luizloyola.autarkia.core.board.Deconstruct.State::priority),
                    DECONSTRUCT_TALLY.listOf().optionalFieldOf("tally", List.of()).forGetter(state -> {
                        List<DeconstructTally> rows = new java.util.ArrayList<>();
                        for (var target : state.targets()) {
                            Pos at = target.at();
                            if (state.trips().containsKey(at) || state.gone().containsKey(at)) {
                                rows.add(new DeconstructTally(at, state.trips().getOrDefault(at, 0),
                                        Optional.ofNullable(state.gone().get(at))));
                            }
                        }
                        return rows;
                    })
            ).apply(project, (targets, why, priority, tally) -> {
                Map<Pos, Integer> trips = new java.util.LinkedHashMap<>();
                Map<Pos, Boolean> gone = new java.util.LinkedHashMap<>();
                for (DeconstructTally row : tally) {
                    if (row.trips() > 0) {
                        trips.put(row.at(), row.trips());
                    }
                    row.gone().ifPresent(g -> gone.put(row.at(), g));
                }
                return new dev.luizloyola.autarkia.core.board.Deconstruct.State(targets, why, priority, trips, gone);
            }));

    /** Everything a {@code site_building} row carries: what to site, and whether it was. */
    public static final MapCodec<dev.luizloyola.autarkia.core.board.SiteBuilding.State> SITE_BUILDING =
            RecordCodecBuilder.mapCodec(project -> project.group(
                    Codec.STRING.fieldOf("blueprint").forGetter(dev.luizloyola.autarkia.core.board.SiteBuilding.State::blueprint),
                    Codec.unboundedMap(Codec.STRING, Codec.STRING).optionalFieldOf("variants", Map.of())
                            .forGetter(dev.luizloyola.autarkia.core.board.SiteBuilding.State::variants),
                    Codec.DOUBLE.fieldOf("priority").forGetter(dev.luizloyola.autarkia.core.board.SiteBuilding.State::priority),
                    Codec.BOOL.optionalFieldOf("done", false).forGetter(dev.luizloyola.autarkia.core.board.SiteBuilding.State::done)
            ).apply(project, dev.luizloyola.autarkia.core.board.SiteBuilding.State::new));

    private static final Codec<dev.luizloyola.autarkia.core.builder.Laying.Use> LAYING_USE =
            RecordCodecBuilder.create(use -> use.group(
                    Codec.STRING.listOf().fieldOf("items").forGetter(u -> List.copyOf(new java.util.TreeSet<>(u.items()))),
                    Codec.BOOL.fieldOf("consumed").forGetter(dev.luizloyola.autarkia.core.builder.Laying.Use::consumed)
            ).apply(use, (items, consumed) -> new dev.luizloyola.autarkia.core.builder.Laying.Use(
                    java.util.Set.copyOf(items), consumed)));

    /** One step of a build, in the world: the plan's copy is a list of these. */
    public static final Codec<dev.luizloyola.autarkia.core.builder.Laying> LAYING =
            RecordCodecBuilder.create(step -> step.group(
                    Codec.STRING.xmap(name -> dev.luizloyola.autarkia.core.builder.Section.valueOf(
                                    name.toUpperCase(java.util.Locale.ROOT)),
                            section -> section.name().toLowerCase(java.util.Locale.ROOT))
                            .fieldOf("section").forGetter(dev.luizloyola.autarkia.core.builder.Laying::section),
                    Codec.STRING.fieldOf("item").forGetter(l -> l.placing().itemId()),
                    POS.fieldOf("at").forGetter(dev.luizloyola.autarkia.core.builder.Laying::cell),
                    Codec.STRING.optionalFieldOf("block", "").forGetter(l -> l.placing().block()),
                    Codec.unboundedMap(Codec.STRING, Codec.STRING).optionalFieldOf("state", Map.of())
                            .forGetter(l -> l.placing().state()),
                    POS.listOf().optionalFieldOf("also", List.of())
                            .forGetter(dev.luizloyola.autarkia.core.builder.Laying::also),
                    POS.fieldOf("stand").forGetter(dev.luizloyola.autarkia.core.builder.Laying::stand),
                    Codec.INT.optionalFieldOf("count", 1).forGetter(dev.luizloyola.autarkia.core.builder.Laying::count),
                    LAYING_USE.listOf().optionalFieldOf("uses", List.of())
                            .forGetter(dev.luizloyola.autarkia.core.builder.Laying::uses),
                    Codec.INT.optionalFieldOf("wave", 0).forGetter(dev.luizloyola.autarkia.core.builder.Laying::wave),
                    Codec.INT.optionalFieldOf("piece", 0).forGetter(dev.luizloyola.autarkia.core.builder.Laying::piece)
            ).apply(step, (section, item, at, block, state, also, stand, count, uses, wave, piece) ->
                    new dev.luizloyola.autarkia.core.builder.Laying(section,
                            new dev.luizloyola.anima.core.brain.act.Placing(item, at, block, state), also, stand,
                            count, uses, wave, piece)));

    private static final Codec<dev.luizloyola.autarkia.core.board.Build.Cooldown> BUILD_COOLDOWN =
            RecordCodecBuilder.create(cooldown -> cooldown.group(
                    Codec.INT.fieldOf("piece").forGetter(dev.luizloyola.autarkia.core.board.Build.Cooldown::piece),
                    Codec.LONG.fieldOf("until").forGetter(dev.luizloyola.autarkia.core.board.Build.Cooldown::until)
            ).apply(cooldown, dev.luizloyola.autarkia.core.board.Build.Cooldown::new));

    private static final Codec<dev.luizloyola.autarkia.core.board.Build.Shortage> BUILD_SHORTAGE =
            RecordCodecBuilder.create(shortage -> shortage.group(
                    Codec.INT.fieldOf("piece").forGetter(dev.luizloyola.autarkia.core.board.Build.Shortage::piece),
                    Codec.unboundedMap(Codec.STRING, Codec.INT).fieldOf("missing")
                            .forGetter(dev.luizloyola.autarkia.core.board.Build.Shortage::missing),
                    Codec.LONG.fieldOf("until").forGetter(dev.luizloyola.autarkia.core.board.Build.Shortage::until)
            ).apply(shortage, dev.luizloyola.autarkia.core.board.Build.Shortage::new));

    /** Everything a {@code build} row carries: the world steps, and which of them stand. */
    public static final MapCodec<dev.luizloyola.autarkia.core.board.Build.State> BUILD =
            RecordCodecBuilder.mapCodec(project -> project.group(
                    UUIDUtil.CODEC.fieldOf("structure").forGetter(dev.luizloyola.autarkia.core.board.Build.State::structure),
                    Codec.STRING.fieldOf("name").forGetter(dev.luizloyola.autarkia.core.board.Build.State::name),
                    Codec.DOUBLE.fieldOf("priority").forGetter(dev.luizloyola.autarkia.core.board.Build.State::priority),
                    LAYING.listOf().fieldOf("order").forGetter(dev.luizloyola.autarkia.core.board.Build.State::order),
                    Codec.INT.listOf().optionalFieldOf("done", List.of())
                            .forGetter(dev.luizloyola.autarkia.core.board.Build.State::done),
                    Codec.INT.listOf().optionalFieldOf("failures", List.of())
                            .forGetter(dev.luizloyola.autarkia.core.board.Build.State::failures),
                    Codec.INT.listOf().optionalFieldOf("refused", List.of())
                            .forGetter(dev.luizloyola.autarkia.core.board.Build.State::refused),
                    UUIDUtil.CODEC.listOf().optionalFieldOf("builders", List.of())
                            .forGetter(state -> state.builders().stream().map(AgentId::value).toList()),
                    BUILD_COOLDOWN.listOf().optionalFieldOf("cooldowns", List.of())
                            .forGetter(dev.luizloyola.autarkia.core.board.Build.State::cooldowns),
                    BUILD_SHORTAGE.listOf().optionalFieldOf("shortages", List.of())
                            .forGetter(dev.luizloyola.autarkia.core.board.Build.State::shortages),
                    Codec.unboundedMap(Codec.STRING, Codec.LONG).optionalFieldOf("unobtainable", Map.of())
                            .forGetter(dev.luizloyola.autarkia.core.board.Build.State::unobtainable)
            ).apply(project, (structure, name, priority, order, done, failures, refused, builders, cooldowns,
                    shortages, unobtainable) -> new dev.luizloyola.autarkia.core.board.Build.State(structure, name,
                    priority, order, done, failures, refused, builders.stream().map(AgentId::new).toList(), cooldowns,
                    shortages, unobtainable)));

    private static <E extends Enum<E>> Codec<E> lowerCase(Class<E> type) {
        return Codec.STRING.comapFlatMap(name -> {
            for (E value : type.getEnumConstants()) {
                if (value.name().equalsIgnoreCase(name)) {
                    return DataResult.success(value);
                }
            }
            return DataResult.error(() -> "no " + type.getSimpleName() + " called \"" + name + "\"");
        }, value -> value.name().toLowerCase(Locale.ROOT));
    }

    /** One want's part in a plot's worth. An infinite measure is a want with nothing known. */
    private static final Codec<HomeJudge.Line> HOME_LINE = RecordCodecBuilder.create(line -> line.group(
            lowerCase(HomeJudge.Want.class).fieldOf("want").forGetter(HomeJudge.Line::want),
            Codec.DOUBLE.fieldOf("measure").forGetter(HomeJudge.Line::measure),
            Codec.DOUBLE.fieldOf("points").forGetter(HomeJudge.Line::points)
    ).apply(line, HomeJudge.Line::new));

    private static final Codec<HomeJudge.Candidate> CANDIDATE = RecordCodecBuilder.create(plot -> plot.group(
            Codec.INT.fieldOf("x").forGetter(HomeJudge.Candidate::x),
            Codec.INT.fieldOf("z").forGetter(HomeJudge.Candidate::z),
            Codec.INT.fieldOf("y").forGetter(HomeJudge.Candidate::y),
            Codec.INT.fieldOf("size").forGetter(HomeJudge.Candidate::size),
            Codec.DOUBLE.fieldOf("value").forGetter(HomeJudge.Candidate::value),
            HOME_LINE.listOf().optionalFieldOf("lines", List.of()).forGetter(HomeJudge.Candidate::lines)
    ).apply(plot, HomeJudge.Candidate::new));

    private static final Codec<HomeSearch.Option> HEADING = RecordCodecBuilder.create(option -> option.group(
            Codec.INT.fieldOf("heading").forGetter(HomeSearch.Option::heading),
            Codec.DOUBLE.fieldOf("score").forGetter(HomeSearch.Option::score),
            POS.optionalFieldOf("leg_end").forGetter(o -> Optional.ofNullable(o.legEnd()))
    ).apply(option, (heading, score, legEnd) -> new HomeSearch.Option(heading, score, legEnd.orElse(null))));

    /** A search for a HOME, down to the leg being walked. */
    public static final Codec<HomeSearch.State> HOME_SEARCH = RecordCodecBuilder.create(search -> search.group(
            POS.optionalFieldOf("start").forGetter(s -> Optional.ofNullable(s.start())),
            POS.listOf().optionalFieldOf("stops", List.of()).forGetter(HomeSearch.State::stops),
            Codec.INT.optionalFieldOf("legs", 0).forGetter(HomeSearch.State::legs),
            Codec.INT.optionalFieldOf("first_at", -1).forGetter(HomeSearch.State::firstAt),
            Codec.INT.optionalFieldOf("heading", -1).forGetter(HomeSearch.State::heading),
            Codec.INT.listOf().optionalFieldOf("blocked", List.of()).forGetter(HomeSearch.State::blocked),
            CANDIDATE.optionalFieldOf("best").forGetter(s -> Optional.ofNullable(s.best())),
            POS.optionalFieldOf("leg_end").forGetter(s -> Optional.ofNullable(s.legEnd())),
            lowerCase(HomeSearch.Phase.class).fieldOf("phase").forGetter(HomeSearch.State::phase),
            HEADING.listOf().optionalFieldOf("options", List.of()).forGetter(HomeSearch.State::options),
            Codec.INT.optionalFieldOf("settle_fails", 0).forGetter(HomeSearch.State::settleFails),
            CANDIDATE.listOf().optionalFieldOf("unreached", List.of()).forGetter(HomeSearch.State::unreached)
    ).apply(search, (start, stops, legs, firstAt, heading, blocked, best, legEnd, phase, options,
            settleFails, unreached) ->
            new HomeSearch.State(start.orElse(null), stops, legs, firstAt, heading, blocked,
                    best.orElse(null), legEnd.orElse(null), phase, options, settleFails, unreached)));

    /** Everything an {@code explore} row carries beyond its kind. */
    public static final MapCodec<Explore.State> EXPLORE =
            RecordCodecBuilder.mapCodec(project -> project.group(
                    UUIDUtil.CODEC.fieldOf("party").forGetter(state -> state.party().value()),
                    Codec.DOUBLE.fieldOf("priority").forGetter(Explore.State::priority),
                    HOME_SEARCH.fieldOf("search").forGetter(Explore.State::search),
                    UUIDUtil.CODEC.optionalFieldOf("scout")
                            .forGetter(state -> state.scout().map(AgentId::value)),
                    GATHER_COOLDOWN.listOf().optionalFieldOf("cooldowns", List.of())
                            .forGetter(Explore.State::cooldowns),
                    Codec.LONG.optionalFieldOf("last_tick", -1L).forGetter(Explore.State::lastTick),
                    UUIDUtil.CODEC.listOf().optionalFieldOf("companions", List.of())
                            .forGetter(state -> state.companions().stream().map(AgentId::value).toList()),
                    UUIDUtil.CODEC.listOf().optionalFieldOf("accompanying", List.of())
                            .forGetter(state -> state.accompanying().stream().map(AgentId::value).toList()),
                    Codec.BOOL.optionalFieldOf("gathered", false).forGetter(Explore.State::gathered),
                    Codec.LONG.optionalFieldOf("step_since", 0L).forGetter(Explore.State::stepSince)
            ).apply(project, (party, priority, search, scout, cooldowns, lastTick, companions,
                    accompanying, gathered, stepSince) -> new Explore.State(PartyId.of(party), priority,
                    search, scout.map(AgentId::of), cooldowns, lastTick,
                    companions.stream().map(AgentId::of).toList(),
                    accompanying.stream().map(AgentId::of).toList(), gathered, stepSince)));

    /**
     * The {@code type} field every row now carries. Unlike {@link #WORK_KEY}'s {@code kind}, this
     * cannot be a bare {@code optionalFieldOf(name, default)} — that omits the field whenever the
     * value already equals the default, and {@code "fell_trees"} IS the default, which is exactly
     * the row a second kind existing must be able to tell apart from. {@code Optional::of} on the
     * way in means the field is written every time; absent still reads as {@code "fell_trees"}, so
     * a pre-dispatch save loads unchanged.
     */
    private static final MapCodec<String> PROJECT_TYPE = Codec.STRING.optionalFieldOf("type")
            .xmap(found -> found.orElse("fell_trees"), Optional::of);

    /**
     * Which flat shape a {@code type} value decodes as. A closed set the codec layer knows by hand,
     * like {@link #workKeyCodecFor} — not {@code PartyProjects}' runtime registry, which answers a
     * different question (how a state RESTORES, not how it reads off disk). An id neither branch
     * claims fails decode outright: the row drops and {@code StoreGuard}'s count catches it, a
     * different accident from an unknown {@code Felling} id inside a row that DID decode.
     */
    private static DataResult<? extends MapCodec<? extends ProjectState>> projectCodecFor(String type) {
        return switch (type) {
            // clear_area: what the project was called until 2026-10-01, in a save written before.
            case "fell_trees", "clear_area" -> DataResult.success(FELL_TREES);
            case "gather" -> DataResult.success(GATHER);
            case "set_up" -> DataResult.success(SET_UP);
            case "explore" -> DataResult.success(EXPLORE);
            case "tend" -> DataResult.success(TEND);
            case "fire" -> DataResult.success(FIRE);
            case "cook" -> DataResult.success(COOK);
            case "flatten" -> DataResult.success(FLATTEN);
            case "clear_plants" -> DataResult.success(CLEAR_PLANTS);
            case "site_building" -> DataResult.success(SITE_BUILDING);
            case "build" -> DataResult.success(BUILD);
            case "deconstruct" -> DataResult.success(DECONSTRUCT);
            default -> DataResult.error(() -> "no project type called \"" + type + "\"");
        };
    }

    /** One posted project, named by its kind so a second kind can be told apart with certainty. */
    public static final Codec<ProjectState> PROJECT = PROJECT_TYPE.partialDispatch(
            state -> DataResult.success(state.type()), PartyBoardCodecs::projectCodecFor);

    private static final MapCodec<WorkKey.AtPlace> AT_PLACE =
            RecordCodecBuilder.mapCodec(place -> place.group(
                    Codec.STRING.fieldOf("flavour").forGetter(WorkKey.AtPlace::flavour),
                    POS.fieldOf("at").forGetter(WorkKey.AtPlace::at)
            ).apply(place, WorkKey.AtPlace::new));

    private static final MapCodec<WorkKey.ForMember> FOR_MEMBER =
            RecordCodecBuilder.mapCodec(member -> member.group(
                    Codec.STRING.fieldOf("flavour").forGetter(WorkKey.ForMember::flavour),
                    UUIDUtil.CODEC.fieldOf("who").forGetter(m -> m.who().value())
            ).apply(member, (flavour, who) -> new WorkKey.ForMember(flavour, AgentId.of(who))));

    private static MapCodec<? extends WorkKey> workKeyCodecFor(String kind) {
        return "for_member".equals(kind) ? FOR_MEMBER : AT_PLACE;
    }

    /**
     * Absent {@code kind} means a place key: every hold written before a trip could be named by its
     * member is one, and a world that lost them would put its settlers back through scoring.
     */
    public static final Codec<WorkKey> WORK_KEY = Codec.STRING.optionalFieldOf("kind", "at_place")
            .dispatch(key -> key instanceof WorkKey.ForMember ? "for_member" : "at_place",
                    PartyBoardCodecs::workKeyCodecFor);

    /** {@code until} and {@code handle} since 2026-09-26; a save without them reads as before. */
    public static final Codec<PartyBoard.Hold> HOLD = RecordCodecBuilder.create(hold -> hold.group(
            WORK_KEY.fieldOf("item").forGetter(PartyBoard.Hold::key),
            UUIDUtil.CODEC.fieldOf("who").forGetter(held -> held.who().value()),
            Codec.LONG.optionalFieldOf("until", 0L).forGetter(PartyBoard.Hold::until)
    ).apply(hold, (key, who, until) -> new PartyBoard.Hold(key, AgentId.of(who), until)));

    /** Since 2026-10-01; a save without it reads as no steps earned. */
    public static final Codec<PartyBoard.Steps> STEPS = RecordCodecBuilder.create(steps -> steps.group(
            WORK_KEY.fieldOf("item").forGetter(PartyBoard.Steps::key),
            Codec.INT.fieldOf("steps").forGetter(PartyBoard.Steps::steps)
    ).apply(steps, PartyBoard.Steps::new));

    /** One posted project and every hold on it — the row a party's board is a list of. */
    public static final Codec<PartyBoard.Row> ROW = RecordCodecBuilder.create(row -> row.group(
            PROJECT.fieldOf("project").forGetter(PartyBoard.Row::project),
            HOLD.listOf().optionalFieldOf("holds", List.of()).forGetter(PartyBoard.Row::holds),
            Codec.INT.optionalFieldOf("handle", 0).forGetter(PartyBoard.Row::handle),
            STEPS.listOf().optionalFieldOf("budget_steps", List.of()).forGetter(PartyBoard.Row::steps)
    ).apply(row, PartyBoard.Row::new));

    /** A board's pacing — its next handle, and who is failing and stood down. */
    public static final Codec<Board.Pacing> PACING = RecordCodecBuilder.create(pacing -> pacing.group(
            Codec.INT.optionalFieldOf("next_handle", 0).forGetter(Board.Pacing::nextHandle),
            Codec.unboundedMap(Codec.STRING.xmap(s -> AgentId.of(java.util.UUID.fromString(s)),
                            who -> who.value().toString()), Codec.INT)
                    .optionalFieldOf("flailing", Map.of()).forGetter(Board.Pacing::flailing),
            Codec.unboundedMap(Codec.STRING.xmap(s -> AgentId.of(java.util.UUID.fromString(s)),
                            who -> who.value().toString()), Codec.LONG)
                    .optionalFieldOf("benched", Map.of()).forGetter(Board.Pacing::benched)
    ).apply(pacing, Board.Pacing::new));
}
