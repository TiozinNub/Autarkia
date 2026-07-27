package dev.luizloyola.autarkia.mod.entity;

import dev.luizloyola.autarkia.core.person.PersonId;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;

/**
 * Turning a {@link PersonId} back into a live entity. Dimension-wide by design: a Person who
 * walked through a portal is still the selected Person, and a lookup that stopped at the overworld
 * would report them "not loaded". A dead or dying body never counts — a stale selection must fail
 * loudly rather than resolve to a corpse.
 */
public final class Persons {
    private Persons() {}

    /** The live Person with this id, searching every dimension, or {@code null} if none is loaded. */
    public static @Nullable Person findLoaded(MinecraftServer server, PersonId id) {
        for (ServerLevel level : server.getAllLevels()) {
            for (Person person : level.getEntities(
                    ModEntities.PERSON, p -> p.isAlive() && id.equals(p.getPersonId()))) {
                return person;
            }
        }
        return null;
    }

    /** Every live Person the server currently has loaded, in no particular order. */
    public static List<Person> loaded(MinecraftServer server) {
        List<Person> out = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            out.addAll(level.getEntities(ModEntities.PERSON, Person::isAlive));
        }
        return out;
    }
}
