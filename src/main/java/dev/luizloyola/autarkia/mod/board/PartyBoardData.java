package dev.luizloyola.autarkia.mod.board;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.luizloyola.anima.compat.SavedDatas;
import dev.luizloyola.anima.core.social.PartyId;
import dev.luizloyola.anima.mod.store.StoreGuard;
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

    /** One party's whole board. */
    private record PartyRow(UUID party, List<PartyBoard.Row> projects) {
    }

    private static final Codec<PartyRow> PARTY_ROW = RecordCodecBuilder.create(row -> row.group(
            UUIDUtil.CODEC.fieldOf("party").forGetter(PartyRow::party),
            PartyBoardCodecs.ROW.listOf().fieldOf("projects").forGetter(PartyRow::projects)
    ).apply(row, PartyRow::new));

    private static final Codec<PartyBoardData> CODEC =
            RecordCodecBuilder.create(data -> data.group(
                    Codec.INT.optionalFieldOf("version", 0).forGetter(d -> SCHEMA),
                    Codec.INT.optionalFieldOf("rows", StoreGuard.UNCOUNTED)
                            .forGetter(d -> d.rows().size()),
                    PARTY_ROW.listOf().fieldOf("boards").forGetter(PartyBoardData::rows)
            ).apply(data, PartyBoardData::fromRows));

    public static final SavedDataType<PartyBoardData> TYPE =
            SavedDatas.type(ID, PartyBoardData::new, CODEC, DataFixTypes.LEVEL);

    /** What came off disk, consumed once by {@link PartyBoards} as it builds the boards. */
    private final Map<PartyId, List<PartyBoard.Row>> loaded;
    private final int loadedVersion;
    private final int declaredRows;

    /**
     * How many rows decoded, fixed at construction.
     *
     * <p>Not {@code loaded.size()}: {@link #take} empties that map as the boards are rebuilt, so a
     * guard running after the restore would see zero against a file claiming several and refuse a
     * healthy world. Which runs first is only {@code SERVER_STARTED} registration order — not a
     * thing a save format should depend on.
     */
    private final int decodedRows;

    /** Set once the boards exist; from then on the live boards answer for what is saved. */
    private @Nullable MinecraftServer live;

    /** Constructs an empty store (the {@link SavedDataType} supplier for a fresh save). */
    public PartyBoardData() {
        this(new LinkedHashMap<>(), StoreGuard.NEVER_LOADED, StoreGuard.UNCOUNTED);
    }

    private PartyBoardData(Map<PartyId, List<PartyBoard.Row>> loaded, int loadedVersion,
                           int declaredRows) {
        this.loaded = loaded;
        this.loadedVersion = loadedVersion;
        this.declaredRows = declaredRows;
        this.decodedRows = loaded.size();
    }

    /** Resolves the single, server-global store (kept on the overworld's data storage). */
    public static PartyBoardData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    @Override
    public int loadedVersion() {
        return loadedVersion;
    }

    @Override
    public int declaredRows() {
        return declaredRows;
    }

    /**
     * How many rows decoded — always what came off DISK, never the live boards. The guard runs at
     * boot to compare the file's own count against what survived parsing; answering with the live
     * count would compare the file against itself and pass every time.
     */
    @Override
    public int actualRows() {
        return decodedRows;
    }

    /** What was saved for this party, and never twice — a board is restored once, at boot. */
    public List<PartyBoard.Row> take(PartyId party) {
        List<PartyBoard.Row> rows = loaded.remove(party);
        return rows == null ? List.of() : rows;
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
            loaded.forEach((party, projects) -> pending.add(new PartyRow(party.value(), projects)));
            return pending;
        }
        long now = live.overworld().getGameTime();
        List<PartyRow> rows = new ArrayList<>();
        for (PartyBoard board : PartyBoards.all(live)) {
            List<PartyBoard.Row> projects = board.snapshot(now);
            if (!projects.isEmpty()) {
                rows.add(new PartyRow(board.party().value(), projects));
            }
        }
        return rows;
    }

    private static PartyBoardData fromRows(int version, int declaredRows, List<PartyRow> rows) {
        Map<PartyId, List<PartyBoard.Row>> loaded = new LinkedHashMap<>();
        for (PartyRow row : rows) {
            loaded.put(PartyId.of(row.party()), row.projects());
        }
        return new PartyBoardData(loaded, version, declaredRows);
    }

    /**
     * Refuses to run a world holding a project this build cannot rebuild.
     *
     * <p>An unknown {@code Clearing} id means a bug or a downgrade; dropping the project would
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
                + "is what removing a Clearing, or downgrading past the one that added it, looks "
                + "like.";
        LOGGER.error(message);
        throw new IllegalStateException(message);
    }
}
