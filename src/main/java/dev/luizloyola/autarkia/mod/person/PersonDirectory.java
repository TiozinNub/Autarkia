package dev.luizloyola.autarkia.mod.person;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.luizloyola.anima.compat.SavedDatas;
import dev.luizloyola.autarkia.core.person.Appearance;
import dev.luizloyola.autarkia.core.person.Gender;
import dev.luizloyola.autarkia.core.person.ModelType;
import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.agent.PrivateIdentity;
import dev.luizloyola.anima.mod.identity.AgentDirectory;
import dev.luizloyola.autarkia.core.person.PersonIdentity;
import dev.luizloyola.autarkia.core.person.PersonNames;
import dev.luizloyola.autarkia.core.person.PersonRegistry;
import dev.luizloyola.autarkia.core.person.PersonSkins;
import dev.luizloyola.autarkia.mod.entity.Person;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.List;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;

/**
 * Every person's full identity, world-scoped and persisted — the {@code mod}-layer home for the
 * pure {@link PersonRegistry}. It lives on the overworld's data storage
 * ({@code <world>/data/autarkia_persons.dat}), stays resident while the server runs and survives
 * reloads, so a {@code Person} holding only an {@link AgentId} can name somebody whose entity is
 * unloaded or was never spawned.
 *
 * <p>Codec-based (the 26.1 {@link SavedDataType} model); the codec lives in {@code mod} so the core
 * stays free of DataFixerUpper.
 */
public final class PersonDirectory extends SavedData implements AgentDirectory {
    private static final Identifier ID = Identifier.fromNamespaceAndPath("autarkia", "persons");

    /** Default external appearance for a legacy (pre-appearance) person. */
    private static final Appearance DEFAULT_APPEARANCE =
            new Appearance(Gender.MALE, Person.DEFAULT_SKIN.toString(), ModelType.WIDE);

    private static final Codec<Gender> GENDER_CODEC = Codec.STRING.xmap(Gender::valueOf, Gender::name);
    private static final Codec<ModelType> MODEL_CODEC = Codec.STRING.xmap(ModelType::valueOf, ModelType::name);

    /** The external, synced tier: {@code {gender, skin, model}}. {@code model} is optional so
     *  entries written before it existed still load (they fall back to {@code WIDE}). */
    private static final Codec<Appearance> APPEARANCE_CODEC = RecordCodecBuilder.create(a -> a.group(
            GENDER_CODEC.fieldOf("gender").forGetter(Appearance::gender),
            Codec.STRING.fieldOf("skin").forGetter(Appearance::skin),
            MODEL_CODEC.optionalFieldOf("model", ModelType.WIDE).forGetter(Appearance::model)
    ).apply(a, Appearance::new));

    /** One identity entry: {@code {id, name, appearance}}. Appearance is optional so entries
     *  written before the external tier existed still load (they get {@link #DEFAULT_APPEARANCE}). */
    private static final Codec<PersonIdentity> ENTRY_CODEC = RecordCodecBuilder.create(entry -> entry.group(
            UUIDUtil.CODEC.fieldOf("id").forGetter(identity -> identity.id().value()),
            Codec.STRING.fieldOf("name").forGetter(PersonIdentity::name),
            APPEARANCE_CODEC.optionalFieldOf("appearance", DEFAULT_APPEARANCE).forGetter(PersonIdentity::appearance)
    ).apply(entry, (uuid, name, appearance) -> new PersonIdentity(AgentId.of(uuid), name, appearance)));

    private static final Codec<PersonDirectory> CODEC = RecordCodecBuilder.create(dir -> dir.group(
            ENTRY_CODEC.listOf().fieldOf("persons").forGetter(PersonDirectory::entries)
    ).apply(dir, PersonDirectory::fromEntries));

    public static final SavedDataType<PersonDirectory> TYPE =
            SavedDatas.type(ID, PersonDirectory::new, CODEC, DataFixTypes.LEVEL);

    private final PersonRegistry registry;

    /** The {@link SavedDataType} supplier for a fresh save. */
    public PersonDirectory() {
        this(new PersonRegistry());
    }

    private PersonDirectory(PersonRegistry registry) {
        this.registry = registry;
    }

    /** Resolves the single, server-global directory. */
    public static PersonDirectory get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    /** Registers a brand-new person with a generated name + appearance, marks dirty, returns it. */
    public PersonIdentity createPerson() {
        RandomGenerator random = ThreadLocalRandom.current();
        Gender gender = Gender.random(random);
        return mint(gender, PersonNames.random(random, gender), random);
    }

    /**
     * As {@link #createPerson()} but with a caller-supplied {@code name} — the deliberate spawn
     * path ({@code /autarkia person spawn <name>}). The name is used verbatim; gender is
     * <em>not</em> inferred from it, so "Alice" may be male.
     */
    public PersonIdentity createPerson(String name) {
        RandomGenerator random = ThreadLocalRandom.current();
        return mint(Gender.random(random), name, random);
    }

    /**
     * Mints and registers one identity: the model follows the skin's geometry (our male skins are
     * wide, female slim — see {@link PersonSkins}). Marks the directory dirty.
     */
    private PersonIdentity mint(Gender gender, String name, RandomGenerator random) {
        String skin = PersonSkins.random(random, gender);
        ModelType model = gender.choose(ModelType.WIDE, ModelType.SLIM);
        PersonIdentity identity = registry.create(AgentId.random(), name, new Appearance(gender, skin, model));
        setDirty();
        return identity;
    }

    public Optional<PersonIdentity> find(AgentId id) {
        return registry.get(id);
    }

    /**
     * Anima's identity lookup, answered for Persons. Anima only ever asks for the private tier
     * ({@link PrivateIdentity#name()}); the public tier — the {@link Appearance} it never needs —
     * stays Autarkia's to sync and render.
     */
    @Override
    public Optional<PrivateIdentity> identity(AgentId id) {
        return registry.get(id).map(PrivateIdentity.class::cast);
    }

    /** Anima's enumeration, answered for Persons — every one, loaded or not. */
    @Override
    public Map<AgentId, PrivateIdentity> known() {
        Map<AgentId, PrivateIdentity> everyone = new LinkedHashMap<>();
        for (PersonIdentity identity : entries()) {
            everyone.put(identity.id(), identity);
        }
        return Collections.unmodifiableMap(everyone);
    }

    /** Dev-tooling removal (the purge command); marks dirty. Real deaths never call this —
     *  identity outlives the entity. */
    public boolean purge(AgentId id) {
        boolean removed = registry.remove(id);
        if (removed) {
            setDirty();
        }
        return removed;
    }

    public Optional<String> nameOf(AgentId id) {
        return registry.get(id).map(PersonIdentity::name);
    }

    public int size() {
        return registry.size();
    }

    /**
     * Every registered identity, loaded or not — for lookups that must reach a person whose entity
     * is unloaded or was never spawned, such as reading an offline person's journal by name.
     */
    public List<PersonIdentity> all() {
        return entries();
    }

    private List<PersonIdentity> entries() {
        return List.copyOf(registry.all());
    }

    private static PersonDirectory fromEntries(List<PersonIdentity> entries) {
        PersonRegistry registry = new PersonRegistry();
        entries.forEach(registry::register);
        return new PersonDirectory(registry);
    }
}
