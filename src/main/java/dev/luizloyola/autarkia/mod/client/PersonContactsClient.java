package dev.luizloyola.autarkia.mod.client;

import dev.luizloyola.autarkia.core.person.PersonId;
import dev.luizloyola.autarkia.mod.net.ContactsPayload;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import org.jspecify.annotations.Nullable;

/**
 * Client-side shadow of this player's contact book, delivered by {@link ContactsPayload} and read
 * by the renderer every frame for the nameplate: the name is no longer in entity data, so this map
 * is the only place a client can learn one.
 *
 * <p>The server owns the book and pushes every change. Cleared on disconnect so names from one
 * world cannot surface in the next.
 */
@Environment(EnvType.CLIENT)
public final class PersonContactsClient {
    private PersonContactsClient() {}

    /** Written by the payload receiver, read by the render thread — hence concurrent. */
    private static final Map<UUID, String> NAMES = new ConcurrentHashMap<>();

    public static void install() {
        ClientPlayNetworking.registerGlobalReceiver(ContactsPayload.TYPE, (payload, context) -> {
            if (payload.replace()) {
                NAMES.clear();
            }
            for (ContactsPayload.Known known : payload.contacts()) {
                NAMES.put(known.id(), known.name());
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> NAMES.clear());
    }

    /** Whether this player has been told who that is. A null id is a Person mid-spawn: not yet. */
    public static boolean knows(@Nullable PersonId id) {
        return id != null && NAMES.containsKey(id.value());
    }

    /** What this player calls them, or {@code null} if they have never been introduced. */
    public static @Nullable String nameOf(@Nullable PersonId id) {
        return id == null ? null : NAMES.get(id.value());
    }
}
