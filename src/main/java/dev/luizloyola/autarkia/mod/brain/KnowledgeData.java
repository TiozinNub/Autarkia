package dev.luizloyola.autarkia.mod.brain;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.luizloyola.autarkia.compat.SavedDatas;
import dev.luizloyola.autarkia.core.brain.knowledge.KnowledgeRegistry;
import dev.luizloyola.autarkia.core.brain.knowledge.PersonKnowledge;
import dev.luizloyola.autarkia.core.brain.knowledge.PoiKind;
import dev.luizloyola.autarkia.core.brain.knowledge.PoiMemory;
import dev.luizloyola.autarkia.core.brain.knowledge.Region;
import dev.luizloyola.autarkia.core.brain.sense.Pos;
import dev.luizloyola.autarkia.core.person.PersonId;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * The world-scoped, persisted home of every person's knowledge — the {@code PersonDirectory}
 * pattern applied to memories, exactly as the brain design's storage section prescribes:
 * durable memory is {@code PersonId}-keyed, attached to the overworld's data storage
 * ({@code <world>/data/autarkia_knowledge.dat}), and outlives every entity. What a person has
 * noticed survives chunk unloads, their death (memories are not physical — the inventory drops,
 * the knowledge doesn't), and server restarts.
 *
 * <p>Serialization is codec-based and lives here so {@code core} stays free of DataFixerUpper.
 * Loading rebuilds the pure {@link KnowledgeRegistry} by replaying {@code note()} — entries
 * were stored post-merge and insertion-ordered, so the replay reproduces the store exactly.
 * Only the durable tier is saved: the claim indexes and pending queues are per-entity sensor
 * state and rebuild from re-walking the world.
 */
public final class KnowledgeData extends SavedData {
    private static final Identifier ID = Identifier.fromNamespaceAndPath("autarkia", "knowledge");

    private static final Codec<Pos> POS_CODEC = BlockPos.CODEC.xmap(
            bp -> new Pos(bp.getX(), bp.getY(), bp.getZ()),
            p -> new BlockPos(p.x(), p.y(), p.z()));

    private static final Codec<PoiKind> KIND_CODEC = Codec.STRING.xmap(PoiKind::valueOf, PoiKind::name);

    private static final Codec<PoiMemory> MEMORY_CODEC = RecordCodecBuilder.create(m -> m.group(
            KIND_CODEC.fieldOf("kind").forGetter(PoiMemory::kind),
            POS_CODEC.fieldOf("anchor").forGetter(PoiMemory::anchor),
            POS_CODEC.fieldOf("min").forGetter(memory -> memory.bounds().min()),
            POS_CODEC.fieldOf("max").forGetter(memory -> memory.bounds().max()),
            Codec.INT.fieldOf("units").forGetter(PoiMemory::units),
            Codec.BOOL.optionalFieldOf("partial", false).forGetter(PoiMemory::partial),
            Codec.LONG.fieldOf("seen").forGetter(PoiMemory::lastSeenTick)
    ).apply(m, (kind, anchor, min, max, units, partial, seen) ->
            new PoiMemory(kind, anchor, new Region(min, max), units, partial, seen)));

    /** One person's remembered POIs, flattened across kinds ({@code kind} rides in each memory). */
    private record PersonEntry(PersonId id, List<PoiMemory> pois) {
    }

    private static final Codec<PersonEntry> ENTRY_CODEC = RecordCodecBuilder.create(e -> e.group(
            UUIDUtil.CODEC.fieldOf("id").forGetter(entry -> entry.id().value()),
            MEMORY_CODEC.listOf().fieldOf("pois").forGetter(PersonEntry::pois)
    ).apply(e, (uuid, pois) -> new PersonEntry(PersonId.of(uuid), pois)));

    private static final Codec<KnowledgeData> CODEC = RecordCodecBuilder.create(d -> d.group(
            ENTRY_CODEC.listOf().fieldOf("persons").forGetter(KnowledgeData::entries)
    ).apply(d, KnowledgeData::fromEntries));

    public static final SavedDataType<KnowledgeData> TYPE =
            SavedDatas.type(ID, KnowledgeData::new, CODEC, DataFixTypes.LEVEL);

    private final KnowledgeRegistry registry;

    /** An empty store (the {@link SavedDataType} supplier for a fresh save). */
    public KnowledgeData() {
        this(new KnowledgeRegistry());
    }

    private KnowledgeData(KnowledgeRegistry registry) {
        this.registry = registry;
    }

    /** The server's knowledge store, loading or creating the overworld-attached instance. */
    public static KnowledgeData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    /** The pure registry this SavedData persists — the object the sensors and commands share. */
    public KnowledgeRegistry registry() {
        return registry;
    }

    private List<PersonEntry> entries() {
        List<PersonEntry> entries = new ArrayList<>();
        for (PersonId id : registry.persons()) {
            PersonKnowledge knowledge = registry.forPerson(id);
            List<PoiMemory> pois = new ArrayList<>();
            for (PoiKind kind : PoiKind.values()) {
                pois.addAll(knowledge.all(kind));
            }
            if (!pois.isEmpty()) {
                entries.add(new PersonEntry(id, pois));
            }
        }
        return entries;
    }

    private static KnowledgeData fromEntries(List<PersonEntry> entries) {
        KnowledgeRegistry registry = new KnowledgeRegistry();
        for (PersonEntry entry : entries) {
            PersonKnowledge knowledge = registry.forPerson(entry.id());
            for (PoiMemory memory : entry.pois()) {
                knowledge.note(memory);
            }
        }
        return new KnowledgeData(registry);
    }
}
