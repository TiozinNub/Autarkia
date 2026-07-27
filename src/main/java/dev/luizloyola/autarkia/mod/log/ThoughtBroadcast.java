package dev.luizloyola.autarkia.mod.log;

import dev.luizloyola.autarkia.core.log.JournalService;
import dev.luizloyola.autarkia.core.person.PersonId;
import dev.luizloyola.autarkia.mod.person.PersonDirectory;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

/**
 * The thinking-out-loud debug channel: tasks narrate their intent through {@code think} journal
 * lines, and this sink forwards an ENABLED person's think-lines to every player's chat as a
 * gray-italic aside, while the journal file keeps the same story for later.
 *
 * <p>Off by default and per-person ({@code /autarkia think} toggles the resolved person), so a
 * fifty-person settlement doesn't shout fifty monologues into chat. The toggle set is transient
 * debug state, wiped with the server like the rings.
 */
public final class ThoughtBroadcast {
    /** Persons currently thinking out loud. Server-thread writes; a concurrent set out of
     *  plain caution about future off-thread readers. */
    private static final Set<PersonId> ENABLED = ConcurrentHashMap.newKeySet();

    private ThoughtBroadcast() {
    }

    /**
     * Attach the chat sink to a server's journal service. Same enqueue-only contract as the
     * file sink — a chat broadcast is a plain game-thread call, no I/O.
     */
    public static void attach(MinecraftServer server, JournalService service) {
        service.subscribe((who, entry) -> {
            if (!"think".equals(entry.event()) || !ENABLED.contains(who)) {
                return;
            }
            String name = PersonDirectory.get(server).nameOf(who).orElse("?");
            server.getPlayerList().broadcastSystemMessage(
                    Component.literal(name + " · " + entry.detail())
                            .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC), false);
        });
    }

    /** Flip narration for this person; returns the new state. */
    public static boolean toggle(PersonId id) {
        if (ENABLED.remove(id)) {
            return false;
        }
        ENABLED.add(id);
        return true;
    }
}
