package dev.luizloyola.autarkia.mod.builder;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.luizloyola.anima.compat.SavedDatas;
import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.social.PartyId;
import dev.luizloyola.anima.mod.store.StoreGuard;
import dev.luizloyola.autarkia.core.bp.Blueprint.Facing;
import dev.luizloyola.autarkia.core.bp.Footprint;
import dev.luizloyola.autarkia.core.bp.Placement;
import dev.luizloyola.autarkia.core.builder.Structure;
import dev.luizloyola.autarkia.mod.board.PartyBoardCodecs;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * Every party's buildings, from the moment a site is chosen ({@code <world>/data/autarkia_structures.dat}).
 * Changed a few times an hour, so every change marks it dirty where it happens; a dropped row is a
 * building its party forgot, hence the guard.
 */
public final class StructuresData extends SavedData implements StoreGuard.Checked {

    public static final Identifier ID = Identifier.fromNamespaceAndPath("autarkia", "structures");

    private static final int SCHEMA = 1;

    static final Codec<Footprint> BOX = RecordCodecBuilder.create(box -> box.group(
            Codec.INT.fieldOf("min_x").forGetter(Footprint::minX),
            Codec.INT.fieldOf("min_z").forGetter(Footprint::minZ),
            Codec.INT.fieldOf("max_x").forGetter(Footprint::maxX),
            Codec.INT.fieldOf("max_z").forGetter(Footprint::maxZ)
    ).apply(box, Footprint::new));

    static final Codec<Placement> PLACEMENT = RecordCodecBuilder.create(placement -> placement.group(
            Codec.STRING.xmap(Facing::valueOf, Facing::name).fieldOf("north").forGetter(Placement::north),
            Codec.BOOL.optionalFieldOf("flip", false).forGetter(Placement::flip)
    ).apply(placement, Placement::new));

    /** Bindings are keyed by material number; a codec map wants string keys. */
    private static final Codec<Map<Integer, String>> BINDINGS = Codec.unboundedMap(Codec.STRING, Codec.STRING)
            .xmap(map -> {
                Map<Integer, String> out = new LinkedHashMap<>();
                map.forEach((k, v) -> out.put(Integer.parseInt(k), v));
                return out;
            }, map -> {
                Map<String, String> out = new LinkedHashMap<>();
                map.forEach((k, v) -> out.put(Integer.toString(k), v));
                return out;
            });

    private static final Codec<Structure.Work> WORK = RecordCodecBuilder.create(w -> w.group(
            UUIDUtil.CODEC.listOf().optionalFieldOf("builders", List.of())
                    .forGetter(work -> work.builders().stream().map(AgentId::value).toList()),
            Codec.LONG.optionalFieldOf("begun_at", -1L).forGetter(Structure.Work::begunAt),
            Codec.LONG.optionalFieldOf("built_at", -1L).forGetter(Structure.Work::builtAt)
    ).apply(w, (builders, begunAt, builtAt) -> new Structure.Work(builders.stream().map(AgentId::new).toList(),
            begunAt, builtAt)));

    static final Codec<Structure> STRUCTURE = RecordCodecBuilder.create(s -> s.group(
            UUIDUtil.CODEC.fieldOf("id").forGetter(Structure::id),
            Codec.STRING.fieldOf("blueprint").forGetter(Structure::blueprint),
            Codec.INT.fieldOf("version").forGetter(Structure::version),
            Codec.unboundedMap(Codec.STRING, Codec.STRING).optionalFieldOf("variants", Map.of())
                    .forGetter(Structure::variants),
            BINDINGS.optionalFieldOf("bindings", Map.of()).forGetter(Structure::bindings),
            PartyBoardCodecs.POS.fieldOf("anchor").forGetter(Structure::anchor),
            PLACEMENT.fieldOf("placement").forGetter(Structure::placement),
            // A record from before the walls were kept reads its pad for them: the wider of the two.
            BOX.optionalFieldOf("built").forGetter(structure -> Optional.of(structure.built())),
            BOX.fieldOf("pad").forGetter(Structure::pad),
            Codec.STRING.xmap(Structure.Phase::valueOf, Structure.Phase::name).fieldOf("phase")
                    .forGetter(Structure::phase),
            Codec.LONG.optionalFieldOf("sited_at", 0L).forGetter(Structure::sitedAt),
            Codec.STRING.optionalFieldOf("note", "").forGetter(Structure::note),
            WORK.optionalFieldOf("work", Structure.Work.NONE).forGetter(Structure::work)
    ).apply(s, (id, blueprint, version, variants, bindings, anchor, placement, built, pad, phase, sitedAt, note,
            work) -> new Structure(id, blueprint, version, variants, bindings, anchor, placement, built.orElse(pad), pad,
            phase, sitedAt, note, work)));

    record Row(UUID party, Structure structure) {
    }

    private static final Codec<Row> ROW = RecordCodecBuilder.create(row -> row.group(
            UUIDUtil.CODEC.fieldOf("party").forGetter(Row::party),
            STRUCTURE.fieldOf("structure").forGetter(Row::structure)
    ).apply(row, Row::new));

    private static final Codec<StructuresData> CODEC = RecordCodecBuilder.create(data -> data.group(
            Codec.INT.optionalFieldOf("version", 0).forGetter(d -> SCHEMA),
            Codec.INT.optionalFieldOf("rows", StoreGuard.UNCOUNTED).forGetter(d -> d.rows().size()),
            ROW.listOf().optionalFieldOf("structures", List.of()).forGetter(StructuresData::rows)
    ).apply(data, StructuresData::new));

    public static final SavedDataType<StructuresData> TYPE =
            SavedDatas.type(ID, StructuresData::new, CODEC, DataFixTypes.LEVEL);

    private final Map<PartyId, List<Structure>> parties = new LinkedHashMap<>();
    private final int loadedVersion;
    private final int declaredRows;
    private final int decodedRows;

    public StructuresData() {
        this(StoreGuard.NEVER_LOADED, StoreGuard.UNCOUNTED, List.of());
    }

    private StructuresData(int loadedVersion, int declaredRows, List<Row> rows) {
        this.loadedVersion = loadedVersion;
        this.declaredRows = declaredRows;
        this.decodedRows = rows.size();
        for (Row row : rows) {
            parties.computeIfAbsent(PartyId.of(row.party()), p -> new ArrayList<>()).add(row.structure());
        }
    }

    public static StructuresData get(MinecraftServer server) {
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

    @Override
    public int actualRows() {
        return decodedRows;
    }

    public Map<PartyId, List<Structure>> parties() {
        return Collections.unmodifiableMap(parties);
    }

    public List<Structure> of(PartyId party) {
        return List.copyOf(parties.getOrDefault(party, List.of()));
    }

    public void add(PartyId party, Structure structure) {
        parties.computeIfAbsent(party, p -> new ArrayList<>()).add(structure);
        setDirty();
    }

    /** Puts {@code changed} in place of the structure with its id; whether there was one. */
    public boolean replace(PartyId party, Structure changed) {
        List<Structure> list = parties.get(party);
        if (list == null) {
            return false;
        }
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id().equals(changed.id())) {
                list.set(i, changed);
                setDirty();
                return true;
            }
        }
        return false;
    }

    public Optional<Structure> find(PartyId party, UUID id) {
        return parties.getOrDefault(party, List.of()).stream().filter(s -> s.id().equals(id)).findFirst();
    }

    private List<Row> rows() {
        List<Row> rows = new ArrayList<>();
        parties.forEach((party, list) -> list.forEach(s -> rows.add(new Row(party.value(), s))));
        return rows;
    }
}
