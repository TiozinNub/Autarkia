package dev.luizloyola.autarkia.mod.board;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.knowledge.Region;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.autarkia.core.board.ClearArea;
import dev.luizloyola.autarkia.core.board.PartyBoard;
import dev.luizloyola.autarkia.core.board.WorkKey;
import java.util.List;
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
     */
    public static final Codec<ClearArea.Phase> PHASE =
            Codec.STRING.xmap(ClearArea.Phase::valueOf, Enum::name);

    public static final Codec<ClearArea.TargetState> TARGET_STATE =
            Codec.STRING.xmap(ClearArea.TargetState::valueOf, Enum::name);

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

    public static final Codec<ClearArea.State> PROJECT =
            RecordCodecBuilder.create(project -> project.group(
                    Codec.STRING.fieldOf("clearing").forGetter(ClearArea.State::clearing),
                    REGION.fieldOf("bounds").forGetter(ClearArea.State::bounds),
                    Codec.DOUBLE.fieldOf("priority").forGetter(ClearArea.State::priority),
                    PHASE.fieldOf("phase").forGetter(ClearArea.State::phase),
                    Codec.INT.listOf().optionalFieldOf("reported", List.of())
                            .forGetter(ClearArea.State::reported),
                    SLICE_COOLDOWN.listOf().optionalFieldOf("cooldowns", List.of())
                            .forGetter(ClearArea.State::sliceCooldowns),
                    TARGET.listOf().optionalFieldOf("targets", List.of())
                            .forGetter(ClearArea.State::targets),
                    Codec.INT.optionalFieldOf("cleared_this_round", 0)
                            .forGetter(ClearArea.State::clearedThisRound),
                    Codec.LONG.optionalFieldOf("pass_started", 0L)
                            .forGetter(ClearArea.State::passStartedAt),
                    // Optional so worlds saved before 2026-08-20 load unchanged: they come back
                    // with an empty pass and re-walk their box once, which is what they did anyway.
                    POS.listOf().optionalFieldOf("swept", List.of())
                            .forGetter(ClearArea.State::swept),
                    // Both optional: a box posted without a destination writes neither, and a world
                    // saved before yards existed loads as exactly that.
                    POS.optionalFieldOf("yard")
                            .forGetter(state -> java.util.Optional.ofNullable(state.yard())),
                    POS.listOf().optionalFieldOf("yard_chests", List.of())
                            .forGetter(ClearArea.State::yardChests)
            ).apply(project, (clearing, bounds, priority, phase, reported, cooldowns, targets,
                    cleared, passStarted, swept, yard, chests) -> new ClearArea.State(
                            clearing, bounds, priority, phase, reported, cooldowns, targets,
                            cleared, passStarted, swept, yard.orElse(null), chests)));

    public static final Codec<WorkKey> WORK_KEY = RecordCodecBuilder.create(key -> key.group(
            Codec.STRING.fieldOf("flavour").forGetter(WorkKey::flavour),
            POS.fieldOf("at").forGetter(WorkKey::at)
    ).apply(key, WorkKey::new));

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
