package dev.luizloyola.autarkia.mod.direction;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.luizloyola.anima.compat.SavedDatas;
import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.knowledge.Region;
import dev.luizloyola.anima.core.social.PartyId;
import dev.luizloyola.anima.mod.store.StoreGuard;
import dev.luizloyola.autarkia.core.direction.DirectionId;
import dev.luizloyola.autarkia.core.direction.PartyProgress;
import dev.luizloyola.autarkia.mod.direction.DirectionsCodecs.PartyRow;
import dev.luizloyola.autarkia.mod.direction.DirectionsCodecs.PersonRow;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * Every party's climb and every person's reached nodes ({@code <world>/data/autarkia_directions.dat}).
 *
 * <p>This object is the authority, unlike the board store: the state changes a few times an hour,
 * not every tick, so every change marks it dirty where it happens. <b>A dropped row is a party
 * losing its history</b>, which evolution never going down forbids — hence the guard.
 */
public final class DirectionsData extends SavedData implements StoreGuard.Checked {

    public static final Identifier ID = Identifier.fromNamespaceAndPath("autarkia", "directions");

    private static final int SCHEMA = 1;

    private static final Codec<DirectionsData> CODEC = RecordCodecBuilder.create(data -> data.group(
            Codec.INT.optionalFieldOf("version", 0).forGetter(d -> SCHEMA),
            Codec.INT.optionalFieldOf("rows", StoreGuard.UNCOUNTED).forGetter(DirectionsData::rowCount),
            DirectionsCodecs.PARTY_ROW.listOf().optionalFieldOf("parties", List.of())
                    .forGetter(DirectionsData::partyRows),
            DirectionsCodecs.PERSON_ROW.listOf().optionalFieldOf("persons", List.of())
                    .forGetter(DirectionsData::personRows)
    ).apply(data, DirectionsData::fromRows));

    public static final SavedDataType<DirectionsData> TYPE =
            SavedDatas.type(ID, DirectionsData::new, CODEC, DataFixTypes.LEVEL);

    private final Map<PartyId, PartyProgress> parties = new LinkedHashMap<>();
    private final Map<AgentId, Set<String>> persons = new LinkedHashMap<>();
    /** Square plots read from a save older than the area, waiting to be claimed as chunks. */
    private final Map<PartyId, Region> oldPlots = new LinkedHashMap<>();
    private final int loadedVersion;
    private final int declaredRows;
    private final int decodedRows;

    public DirectionsData() {
        this(StoreGuard.NEVER_LOADED, StoreGuard.UNCOUNTED, List.of(), List.of());
    }

    private DirectionsData(int loadedVersion, int declaredRows, List<PartyRow> partyRows,
                           List<PersonRow> personRows) {
        this.loadedVersion = loadedVersion;
        this.declaredRows = declaredRows;
        this.decodedRows = partyRows.size() + personRows.size();
        for (PartyRow row : partyRows) {
            PartyProgress progress = new PartyProgress();
            row.reached().forEach(progress::reach);
            row.checkpoints().forEach(progress::complete);
            progress.home(row.home().map(DirectionsCodecs.SavedHome::home).orElse(null));
            row.stalls().forEach(saved -> progress.stall(saved.direction(), saved.stall()));
            parties.put(PartyId.of(row.party()), progress);
            row.home().flatMap(DirectionsCodecs.SavedHome::plot)
                    .ifPresent(plot -> oldPlots.put(PartyId.of(row.party()), plot));
        }
        for (PersonRow row : personRows) {
            persons.put(AgentId.of(row.agent()), new LinkedHashSet<>(row.reached()));
        }
    }

    private static DirectionsData fromRows(int version, int declared, List<PartyRow> partyRows,
                                           List<PersonRow> personRows) {
        return new DirectionsData(version, declared, partyRows, personRows);
    }

    public static DirectionsData get(MinecraftServer server) {
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

    /** What came off disk, fixed at load — never the live count, or the guard would pass itself. */
    @Override
    public int actualRows() {
        return decodedRows;
    }

    // ── parties ─────────────────────────────────────────────────────────────────────────────

    /** Every party with a climb worth keeping, in the order they began. */
    /** The old plots read off disk; whoever claims them empties this. */
    public Map<PartyId, Region> oldPlots() {
        return oldPlots;
    }

    public Map<PartyId, PartyProgress> parties() {
        return Collections.unmodifiableMap(parties);
    }

    public Optional<PartyProgress> find(PartyId party) {
        return Optional.ofNullable(parties.get(party));
    }

    /** This party's climb, begun on first ask. */
    public PartyProgress progress(PartyId party) {
        return parties.computeIfAbsent(party, p -> new PartyProgress());
    }

    // ── persons ─────────────────────────────────────────────────────────────────────────────

    public Set<String> reachedBy(AgentId who) {
        Set<String> reached = persons.get(who);
        return reached == null ? Set.of() : Collections.unmodifiableSet(reached);
    }

    /** Adds to what this person has reached; whether it grew. Nothing but a revoke shrinks it. */
    public boolean personReach(AgentId who, Collection<String> nodes) {
        boolean grew = persons.computeIfAbsent(who, w -> new LinkedHashSet<>()).addAll(nodes);
        if (grew) {
            setDirty();
        }
        return grew;
    }

    /** An operator's revoke from one person. */
    public boolean personRevoke(AgentId who, Collection<String> nodes) {
        Set<String> reached = persons.get(who);
        boolean shrank = reached != null && reached.removeAll(nodes);
        if (shrank) {
            setDirty();
        }
        return shrank;
    }

    // ── rows ────────────────────────────────────────────────────────────────────────────────

    private List<PartyRow> partyRows() {
        List<PartyRow> rows = new ArrayList<>();
        parties.forEach((party, progress) -> {
            if (!progress.isEmpty()) {
                rows.add(new PartyRow(party.value(), List.copyOf(progress.reached()),
                        List.<DirectionId>copyOf(progress.checkpoints()),
                        Optional.ofNullable(progress.home())
                                .map(home -> new DirectionsCodecs.SavedHome(home, Optional.empty())),
                        progress.stalls().entrySet().stream()
                                .map(stall -> new DirectionsCodecs.SavedStall(stall.getKey(), stall.getValue()))
                                .toList()));
            }
        });
        return rows;
    }

    private List<PersonRow> personRows() {
        List<PersonRow> rows = new ArrayList<>();
        persons.forEach((who, reached) -> {
            if (!reached.isEmpty()) {
                rows.add(new PersonRow(who.value(), List.copyOf(reached)));
            }
        });
        return rows;
    }

    private int rowCount() {
        return partyRows().size() + personRows().size();
    }
}
