package dev.luizloyola.autarkia.mod.person;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.luizloyola.autarkia.core.person.PersonId;
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
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The world-scoped, persisted store of every person's full identity — the {@code mod}-layer home
 * for the pure {@link PersonRegistry}, on the overworld's data storage
 * ({@code <world>/data/autarkia_persons.dat}). Identity outlives the entity: a loaded
 * {@code Person} holds only a {@link PersonId} and looks up a name here even for someone unloaded
 * or never spawned.
 *
 * <p>The codec lives in {@code mod} rather than {@code core} so the core stays free of
 * DataFixerUpper.
 */
public final class PersonDirectory extends SavedData {
    private static final Identifier ID = Identifier.fromNamespaceAndPath("autarkia", "persons");

    /** One identity entry: {@code {id, name}}. */
    private static final Codec<PersonIdentity> ENTRY_CODEC = RecordCodecBuilder.create(entry -> entry.group(
            UUIDUtil.CODEC.fieldOf("id").forGetter(identity -> identity.id().value()),
            Codec.STRING.fieldOf("name").forGetter(PersonIdentity::name)
    ).apply(entry, (uuid, name) -> new PersonIdentity(PersonId.of(uuid), name)));

    private static final Codec<PersonDirectory> CODEC = RecordCodecBuilder.create(dir -> dir.group(
            ENTRY_CODEC.listOf().fieldOf("persons").forGetter(PersonDirectory::entries)
    ).apply(dir, PersonDirectory::fromEntries));

    public static final SavedDataType<PersonDirectory> TYPE =
            new SavedDataType<>(ID, PersonDirectory::new, CODEC, DataFixTypes.LEVEL);

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

    /** Registers a brand-new person with a generated name, marks the store dirty, returns it. */
    public PersonIdentity createPerson() {
        String name = PersonNames.random(ThreadLocalRandom.current());
        PersonIdentity identity = registry.create(PersonId.random(), name);
        setDirty();
        return identity;
    }

    public Optional<PersonIdentity> find(PersonId id) {
        return registry.get(id);
    }

    public Optional<String> nameOf(PersonId id) {
        return registry.get(id).map(PersonIdentity::name);
    }

    public int size() {
        return registry.size();
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
