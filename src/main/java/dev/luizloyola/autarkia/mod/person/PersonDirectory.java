package dev.luizloyola.autarkia.mod.person;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.luizloyola.anima.compat.SavedDatas;
import dev.luizloyola.autarkia.core.person.Appearance;
import dev.luizloyola.autarkia.core.person.Gender;
import dev.luizloyola.autarkia.core.person.Genotypes;
import dev.luizloyola.autarkia.core.person.Look;
import dev.luizloyola.autarkia.core.person.ModelType;
import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.appearance.catalog.Catalog;
import dev.luizloyola.anima.core.agent.PrivateIdentity;
import dev.luizloyola.anima.mod.identity.AgentDirectory;
import dev.luizloyola.anima.mod.store.StoreGuard;
import dev.luizloyola.autarkia.core.person.PersonIdentity;
import dev.luizloyola.autarkia.core.person.PersonNames;
import dev.luizloyola.autarkia.core.person.PersonRegistry;
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
public final class PersonDirectory extends SavedData
        implements AgentDirectory, StoreGuard.Checked {
    /** This store's file key — public so the boot guard can find it on disk. */
    public static final Identifier ID = Identifier.fromNamespaceAndPath("autarkia", "persons");

    /** One identity entry: {@code {id, name, appearance}}. Appearance is optional so entries
     *  written before the external tier existed still load (they get {@link Appearance#DEFAULT});
     *  its own two forms are {@link AppearanceCodecs}, which lives apart so it can be tested. */
    private static final Codec<PersonIdentity> ENTRY_CODEC = RecordCodecBuilder.create(entry -> entry.group(
            UUIDUtil.CODEC.fieldOf("id").forGetter(identity -> identity.id().value()),
            Codec.STRING.fieldOf("name").forGetter(PersonIdentity::name),
            AppearanceCodecs.CODEC.optionalFieldOf("appearance", Appearance.DEFAULT)
                    .forGetter(PersonIdentity::appearance)
    ).apply(entry, (uuid, name, appearance) -> new PersonIdentity(AgentId.of(uuid), name, appearance)));

    /** This store's schema. Bump when the shape above changes incompatibly.
     *  <p>2 — appearance became one encoded string ({@link Appearance#encode()}) instead of a
     *  {@code {gender, skin, model}} compound. Schema 1 still reads. */
    private static final int SCHEMA = 2;

    private static final Codec<PersonDirectory> CODEC = RecordCodecBuilder.create(dir -> dir.group(
            Codec.INT.optionalFieldOf("version", 0).forGetter(d -> SCHEMA),
            Codec.INT.optionalFieldOf("rows", StoreGuard.UNCOUNTED)
                    .forGetter(d -> d.entries().size()),
            ENTRY_CODEC.listOf().fieldOf("persons").forGetter(PersonDirectory::entries)
    ).apply(dir, PersonDirectory::fromEntries));

    public static final SavedDataType<PersonDirectory> TYPE =
            SavedDatas.type(ID, PersonDirectory::new, CODEC, DataFixTypes.LEVEL);

    private final PersonRegistry registry;
    private final int loadedVersion;
    private final int declaredRows;

    /** The {@link SavedDataType} supplier for a fresh save. */
    public PersonDirectory() {
        this(new PersonRegistry(), StoreGuard.NEVER_LOADED, StoreGuard.UNCOUNTED);
    }

    private PersonDirectory(PersonRegistry registry, int loadedVersion, int declaredRows) {
        this.registry = registry;
        this.loadedVersion = loadedVersion;
        this.declaredRows = declaredRows;
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
        return entries().size();
    }

    /** Resolves the single, server-global directory. */
    public static PersonDirectory get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    /** Registers a brand-new person with a generated name + appearance, marks dirty, returns it. */
    public PersonIdentity createPerson() {
        RandomGenerator random = ThreadLocalRandom.current();
        Gender gender = Gender.random(random);
        return mint(gender, PersonNames.random(random, gender));
    }

    /**
     * As {@link #createPerson()} but named by the caller ({@code /autarkia person spawn <name>}).
     * Gender, look and model are still generated; gender is <em>not</em> inferred from the name, so
     * "Alice" may be male.
     */
    public PersonIdentity createPerson(String name) {
        RandomGenerator random = ThreadLocalRandom.current();
        return mint(Gender.random(random), name);
    }

    /**
     * Mints and registers one identity from a chosen gender + name. The model follows the gender
     * (male wide, female slim); everything else is rolled from their own id.
     */
    private PersonIdentity mint(Gender gender, String name) {
        ModelType model = gender.choose(ModelType.WIDE, ModelType.SLIM);
        AgentId id = AgentId.random();
        PersonIdentity identity = registry.create(id, name, new Appearance(gender, model, look(id, gender)));
        setDirty();
        return identity;
    }

    /**
     * The look a brand-new person is given: composed from the catalog, seeded from their own id.
     *
     * <p>Seeded from the id, not a passing random, so the roll is a <em>function of the person</em>:
     * one settler keeps one face, a child's seed can later mix two parents', and a look is
     * reproducible. Public because the self-healing migration gives a person whose bundled skin no
     * longer exists the look they would have been born with.
     */
    public static Look composedLookFor(AgentId id, Gender gender) {
        return look(id, gender);
    }

    private static Look look(AgentId id, Gender gender) {
        Catalog catalog = PersonAppearance.catalog();
        if (catalog == null) {
            // No catalog is a broken installation, not a style of settler. One recognisable fallback
            // says so at a glance; nine of them at random would look like a working feature.
            return Look.DEFAULT;
        }
        // Art is split into male/ and female/ folders, so "what shirts exist" has two answers:
        // rolling the merged list would hand a man a woman-only shirt, whose torso then silently
        // draws nothing.
        return Genotypes.roll(id.value().getMostSignificantBits() ^ id.value().getLeastSignificantBits(),
                catalog, PersonAppearance.choicesGiven(
                        Map.of("gender", gender.name().toLowerCase(java.util.Locale.ROOT))));
    }

    /**
     * Overwrites an existing identity in place — same id, corrected content. Used by the
     * self-healing skin migration; anything that changes what a person is should go through a
     * named operation rather than this.
     */
    public void replace(PersonIdentity identity) {
        registry.register(identity);
        setDirty();
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

    /** Drops the identity. Real deaths never call this — identity outlives the entity by design
     *  (see {@link PersonRegistry#remove}). No caller today: {@code purge graveyard} used it and
     *  treated every unloaded Person as dead. */
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

    private static PersonDirectory fromEntries(int version, int declaredRows,
                                               List<PersonIdentity> entries) {
        PersonRegistry registry = new PersonRegistry();
        entries.forEach(registry::register);
        return new PersonDirectory(registry, version, declaredRows);
    }
}
