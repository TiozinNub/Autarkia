package dev.luizloyola.autarkia.mod.board;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.luizloyola.anima.compat.SavedDatas;
import dev.luizloyola.anima.core.social.PartyId;
import dev.luizloyola.anima.mod.store.StoreGuard;
import dev.luizloyola.autarkia.core.board.Board;
import dev.luizloyola.autarkia.core.board.PartyBoard;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The persisted home of every party's board ({@code <world>/data/autarkia_party_boards.dat}). Not
 * optional: anything outliving its tick persists (the rule since 2026-08-05), and a clearing that
 * forgot its ledger on relog would re-walk ground it had already surveyed and re-fell trees that
 * are not there.
 *
 * <p><b>The live boards are the authority, not this object.</b> Mirroring a board that changes
 * every heartbeat would be a write per tick per lease, so the codec's getter reads the live boards
 * at the moment vanilla asks and the host only marks the store dirty while anything is posted.
 * What is held here between a load and the boards being built is the other direction: rows off
 * disk, awaiting {@link PartyBoards}.
 */
public final class PartyBoardData extends SavedData implements StoreGuard.Checked {

    private static final Logger LOGGER = LoggerFactory.getLogger("autarkia/board");

    /** This store's file key — public so the boot guard can find it on disk. */
    public static final Identifier ID =
            Identifier.fromNamespaceAndPath("autarkia", "party_boards");

    /** This store's schema. Bump when the shape below changes incompatibly. */
    private static final int SCHEMA = 1;

    /** One party's whole board: its projects and its pacing. */
    private record PartyRow(UUID party, List<PartyBoard.Row> projects, Board.Pacing pacing) {
    }

    /** What was saved for one party. */
    public record Saved(List<PartyBoard.Row> projects, Board.Pacing pacing) {
        static final Saved NONE = new Saved(List.of(), Board.Pacing.NONE);
    }

    private static final Codec<PartyRow> PARTY_ROW = RecordCodecBuilder.create(row -> row.group(
            UUIDUtil.CODEC.fieldOf("party").forGetter(PartyRow::party),
            PartyBoardCodecs.ROW.listOf().fieldOf("projects").forGetter(PartyRow::projects),
            PartyBoardCodecs.PACING.optionalFieldOf("pacing", Board.Pacing.NONE)
                    .forGetter(PartyRow::pacing)
    ).apply(row, PartyRow::new));

    /**
     * {@code rows} counts parties, so a project or hold dropped inside a party that still decoded
     * passes it; {@code entries} counts those. A count of its own rather than a new meaning for
     * {@code rows}, which would refuse every save written before it.
     */
    static final Codec<PartyBoardData> CODEC =
            RecordCodecBuilder.create(data -> data.group(
                    Codec.INT.optionalFieldOf("version", 0).forGetter(d -> SCHEMA),
                    Codec.INT.optionalFieldOf("rows", StoreGuard.UNCOUNTED)
                            .forGetter(d -> d.rows().size()),
                    Codec.INT.optionalFieldOf("entries", StoreGuard.UNCOUNTED)
                            .forGetter(d -> entries(d.rows())),
                    PARTY_ROW.listOf().fieldOf("boards").forGetter(PartyBoardData::rows)
            ).apply(data, PartyBoardData::fromRows));

    public static final SavedDataType<PartyBoardData> TYPE =
            SavedDatas.type(ID, PartyBoardData::new, CODEC, DataFixTypes.LEVEL);

    /** What came off disk, consumed once by {@link PartyBoards} as it builds the boards. */
    private final Map<PartyId, Saved> loaded;
    private final int loadedVersion;
    private final int declaredRows;
    private final int declaredEntries;

    /**
     * How many rows decoded, fixed at construction.
     *
     * <p>Not {@code loaded.size()}: {@link #take} empties that map as the boards are rebuilt, so a
     * guard running after the restore would see zero against a file claiming several and refuse a
     * healthy world. Which runs first is only {@code SERVER_STARTED} registration order — not a
     * thing a save format should depend on.
     */
    private final int decodedRows;
    /** Projects plus holds that decoded, fixed at construction for the same reason. */
    private final int decodedEntries;

    /** Set once the boards exist; from then on the live boards answer for what is saved. */
    private @Nullable MinecraftServer live;

    /** Constructs an empty store (the {@link SavedDataType} supplier for a fresh save). */
    public PartyBoardData() {
        this(new LinkedHashMap<>(), StoreGuard.NEVER_LOADED, StoreGuard.UNCOUNTED,
                StoreGuard.UNCOUNTED, 0);
    }

    private PartyBoardData(Map<PartyId, Saved> loaded, int loadedVersion,
                           int declaredRows, int declaredEntries, int decodedEntries) {
        this.loaded = loaded;
        this.loadedVersion = loadedVersion;
        this.declaredRows = declaredRows;
        this.declaredEntries = declaredEntries;
        this.decodedRows = loaded.size();
        this.decodedEntries = decodedEntries;
    }

    /** Resolves the single, server-global store (kept on the overworld's data storage). */
    public static PartyBoardData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    @Override
    public int loadedVersion() {
        return loadedVersion;
    }

    /**
     * Parties plus {@code entries} when the file has both, so the guard's one comparison sees a
     * lost project or hold too: a partial parse only ever drops, so the sums differ exactly when
     * either count does. A save from before {@code entries} is checked on parties alone.
     */
    @Override
    public int declaredRows() {
        return declaredRows == StoreGuard.UNCOUNTED || declaredEntries == StoreGuard.UNCOUNTED
                ? declaredRows
                : declaredRows + declaredEntries;
    }

    /**
     * What decoded, counted as {@link #declaredRows} counts — always what came off DISK, never the
     * live boards. The guard runs at boot to compare the file's own count against what survived
     * parsing; answering with the live count would compare the file against itself and pass every
     * time.
     */
    @Override
    public int actualRows() {
        return declaredRows == StoreGuard.UNCOUNTED || declaredEntries == StoreGuard.UNCOUNTED
                ? decodedRows
                : decodedRows + decodedEntries;
    }

    /** What was saved for this party, and never twice — a board is restored once, at boot. */
    public Saved take(PartyId party) {
        Saved saved = loaded.remove(party);
        return saved == null ? Saved.NONE : saved;
    }

    /** Every party that had a board on disk. */
    public List<PartyId> parties() {
        return List.copyOf(loaded.keySet());
    }

    /** From here on, saving means asking the live boards. */
    public void attach(MinecraftServer server) {
        this.live = server;
    }

    /**
     * The rows to write: the live boards when they exist, and otherwise whatever came off disk and
     * has not been handed back yet.
     *
     * <p>Reading live state inside the codec's getter is what keeps the file current without a
     * write per heartbeat — vanilla calls this at the moment it serializes.
     */
    private List<PartyRow> rows() {
        if (live == null) {
            List<PartyRow> pending = new ArrayList<>();
            loaded.forEach((party, saved) -> pending.add(
                    new PartyRow(party.value(), saved.projects(), saved.pacing())));
            return pending;
        }
        long now = live.overworld().getGameTime();
        List<PartyRow> rows = new ArrayList<>();
        for (PartyBoard board : PartyBoards.all(live)) {
            List<PartyBoard.Row> projects = board.snapshot(now);
            Board.Pacing pacing = board.pacing(now);
            if (!projects.isEmpty() || !pacing.isEmpty()) {
                rows.add(new PartyRow(board.party().value(), projects, pacing));
            }
        }
        return rows;
    }

    private static PartyBoardData fromRows(int version, int declaredRows, int declaredEntries,
                                           List<PartyRow> rows) {
        Map<PartyId, Saved> loaded = new LinkedHashMap<>();
        for (PartyRow row : rows) {
            loaded.put(PartyId.of(row.party()), new Saved(row.projects(), row.pacing()));
        }
        return new PartyBoardData(loaded, version, declaredRows, declaredEntries, entries(rows));
    }

    /** Every project and every hold on it, across all boards. */
    private static int entries(List<PartyRow> rows) {
        int entries = 0;
        for (PartyRow row : rows) {
            for (PartyBoard.Row project : row.projects()) {
                entries += 1 + project.holds().size();
            }
        }
        return entries;
    }

    /**
     * Refuses to run a world holding a project this build cannot rebuild.
     *
     * <p>An unknown {@code Felling} id means a bug or a downgrade; dropping the project would
     * silently empty a party's work board and the next save would make it permanent — the failure
     * {@code StoreGuard} exists to prevent, one layer up.
     */
    static void refuseUnknown(int unknown, PartyId party) {
        if (unknown == 0) {
            return;
        }
        String message = "Autarkia refused to start: " + unknown + " saved project(s) on party "
                + party.value() + " name a kind of clearing this build does not have. Dropping them "
                + "would empty that party's board and the next save would make it permanent. This "
                + "is what removing a Felling, or downgrading past the one that added it, looks "
                + "like.";
        LOGGER.error(message);
        throw new IllegalStateException(message);
    }
}
