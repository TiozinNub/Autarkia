package dev.luizloyola.autarkia.mod.board;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.knowledge.CoverageGrid;
import dev.luizloyola.anima.core.brain.knowledge.Region;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.core.social.PartyId;
import dev.luizloyola.autarkia.core.board.ClearArea;
import dev.luizloyola.autarkia.core.board.Gather;
import dev.luizloyola.autarkia.core.board.PartyBoard;
import dev.luizloyola.autarkia.core.board.ProjectState;
import dev.luizloyola.autarkia.core.board.WorkKey;
import java.util.HashSet;
import java.util.List;
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
     * {@link ClearArea#phaseByName}. Encoding still writes the real name.
     */
    public static final Codec<ClearArea.Phase> PHASE =
            Codec.STRING.xmap(ClearArea::phaseByName, Enum::name);

    public static final Codec<ClearArea.TargetState> TARGET_STATE =
            Codec.STRING.xmap(ClearArea.TargetState::valueOf, Enum::name);

    public static final Codec<ClearArea.CellMask> CELL_MASK =
            RecordCodecBuilder.create(cell -> cell.group(
                    POS.fieldOf("at").forGetter(ClearArea.CellMask::corner),
                    Codec.INT.fieldOf("mask").forGetter(ClearArea.CellMask::mask)
            ).apply(cell, ClearArea.CellMask::new));

    public static final Codec<ClearArea.Target> TARGET =
            RecordCodecBuilder.create(target -> target.group(
                    POS.fieldOf("at").forGetter(ClearArea.Target::anchor),
                    TARGET_STATE.fieldOf("state").forGetter(ClearArea.Target::state),
                    Codec.INT.optionalFieldOf("failures", 0).forGetter(ClearArea.Target::failures),
                    Codec.LONG.optionalFieldOf("retry", 0L).forGetter(ClearArea.Target::retryAfter),
                    UUIDUtil.CODEC.listOf().optionalFieldOf("failed_by", List.of())
                            .forGetter(t -> t.failedBy().stream().map(AgentId::value).toList())
            ).apply(target, (at, state, failures, retry, failedBy) -> new ClearArea.Target(
                    at, state, failures, retry, failedBy.stream().map(AgentId::of).toList())));

    public static final Codec<ClearArea.SliceCooldown> SLICE_COOLDOWN =
            RecordCodecBuilder.create(cooldown -> cooldown.group(
                    Codec.INT.fieldOf("slice").forGetter(ClearArea.SliceCooldown::slice),
                    Codec.LONG.fieldOf("retry").forGetter(ClearArea.SliceCooldown::retryAfter)
            ).apply(cooldown, ClearArea.SliceCooldown::new));

    /**
     * Everything a {@code clear_area} row carries beyond its kind — a {@link MapCodec} rather than
     * the plain {@link Codec} this used to be, because {@link #PROJECT} below needs its fields flat
     * in the same object as {@code type}, not nested under a sub-key.
     */
    public static final MapCodec<ClearArea.State> CLEAR_AREA =
            RecordCodecBuilder.mapCodec(project -> project.group(
                    Codec.STRING.fieldOf("clearing").forGetter(ClearArea.State::clearing),
                    REGION.fieldOf("bounds").forGetter(ClearArea.State::bounds),
                    Codec.DOUBLE.fieldOf("priority").forGetter(ClearArea.State::priority),
                    PHASE.fieldOf("phase").forGetter(ClearArea.State::phase),
                    SLICE_COOLDOWN.listOf().optionalFieldOf("cooldowns", List.of())
                            .forGetter(ClearArea.State::sliceCooldowns),
                    TARGET.listOf().optionalFieldOf("targets", List.of())
                            .forGetter(ClearArea.State::targets),
                    Codec.INT.optionalFieldOf("felled_since_reopen", 0)
                            .forGetter(ClearArea.State::felledSinceReopen),
                    // The frontier itself. A short read here is a box re-swept from scratch, which
                    // is what StoreGuard's row count is for.
                    CELL_MASK.listOf().optionalFieldOf("covered", List.of())
                            .forGetter(ClearArea.State::covered),
                    // Pre-2026-08-23 saves listed whole settled corners under this name. Read them
                    // as fully covered cells so a live world survives the change rather than losing
                    // its sweep; never written, since a fresh save always has "covered" instead.
                    POS.listOf().optionalFieldOf("swept", List.of())
                            .forGetter(state -> List.<Pos>of()),
                    // Both optional: a box posted without a destination writes neither, and a world
                    // saved before yards existed loads as exactly that.
                    POS.optionalFieldOf("yard")
                            .forGetter(state -> java.util.Optional.ofNullable(state.yard())),
                    POS.listOf().optionalFieldOf("yard_chests", List.of())
                            .forGetter(ClearArea.State::yardChests)
            ).apply(project, (clearing, bounds, priority, phase, cooldowns, targets, felled,
                    covered, legacySwept, yard, chests) -> new ClearArea.State(
                            clearing, bounds, priority, phase, cooldowns, targets, felled,
                            covered.isEmpty()
                                    ? legacySwept.stream()
                                            .map(at -> new ClearArea.CellMask(at, CoverageGrid.FULL))
                                            .toList()
                                    : covered,
                            yard.orElse(null), chests)));

    /** What one yard chest held when somebody last looked, and when they looked. */
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
                    POS.fieldOf("yard").forGetter(Gather.State::yard),
                    Codec.DOUBLE.fieldOf("priority").forGetter(Gather.State::priority),
                    UUIDUtil.CODEC.fieldOf("party").forGetter(state -> state.party().value()),
                    Codec.STRING.fieldOf("split").forGetter(Gather.State::split),
                    POS.listOf().optionalFieldOf("yard_chests", List.of())
                            .forGetter(Gather.State::yardChests),
                    READING.listOf().optionalFieldOf("readings", List.of())
                            .forGetter(Gather.State::readings),
                    TRIP.listOf().optionalFieldOf("trips", List.of())
                            .forGetter(Gather.State::trips)
            ).apply(project, (spec, target, yard, priority, party, split, chests, readings, trips) ->
                    new Gather.State(spec, target, yard, priority, PartyId.of(party), split,
                            chests, readings, trips)));

    /**
     * The {@code type} field every row now carries. Unlike {@link #WORK_KEY}'s {@code kind}, this
     * cannot be a bare {@code optionalFieldOf(name, default)} — that omits the field whenever the
     * value already equals the default, and {@code "clear_area"} IS the default, which is exactly
     * the row a second kind existing must be able to tell apart from. {@code Optional::of} on the
     * way in means the field is written every time; absent still reads as {@code "clear_area"}, so
     * a pre-dispatch save loads unchanged.
     */
    private static final MapCodec<String> PROJECT_TYPE = Codec.STRING.optionalFieldOf("type")
            .xmap(found -> found.orElse("clear_area"), Optional::of);

    /**
     * Which flat shape a {@code type} value decodes as. A closed set the codec layer knows by hand,
     * like {@link #workKeyCodecFor} — not {@code PartyProjects}' runtime registry, which answers a
     * different question (how a state RESTORES, not how it reads off disk). An id neither branch
     * claims fails decode outright: the row drops and {@code StoreGuard}'s count catches it, a
     * different accident from an unknown {@code Clearing} id inside a row that DID decode.
     */
    private static DataResult<? extends MapCodec<? extends ProjectState>> projectCodecFor(String type) {
        return switch (type) {
            case "clear_area" -> DataResult.success(CLEAR_AREA);
            case "gather" -> DataResult.success(GATHER);
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

    public static final Codec<PartyBoard.Hold> HOLD = RecordCodecBuilder.create(hold -> hold.group(
            WORK_KEY.fieldOf("item").forGetter(PartyBoard.Hold::key),
            UUIDUtil.CODEC.fieldOf("who").forGetter(held -> held.who().value())
    ).apply(hold, (key, who) -> new PartyBoard.Hold(key, AgentId.of(who))));

    /** One posted project and every hold on it — the row a party's board is a list of. */
    public static final Codec<PartyBoard.Row> ROW = RecordCodecBuilder.create(row -> row.group(
            PROJECT.fieldOf("project").forGetter(PartyBoard.Row::project),
            HOLD.listOf().optionalFieldOf("holds", List.of()).forGetter(PartyBoard.Row::holds)
    ).apply(row, PartyBoard.Row::new));
}
