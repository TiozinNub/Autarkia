package dev.luizloyola.autarkia.mod.brain;

import dev.luizloyola.autarkia.core.brain.sense.Peer;
import dev.luizloyola.autarkia.core.brain.sense.PeerEvent;
import dev.luizloyola.autarkia.core.person.PersonId;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/**
 * The peer viewer — {@code KnowledgeViewer}'s sibling for the people sense: {@code /autarkia
 * peers view true} narrates every {@link PeerEvent} a person perceives to chat. Her PERCEPTION,
 * not the world: losing track of someone standing behind her back is the viewer working.
 *
 * <p>A player's toggle whispers to that player; a CONSOLE toggle broadcasts, reaching the server
 * log — which is what makes the viewer usable from the headless harness. Transient debug state,
 * one watcher map per server, gone on stop.
 */
public final class PeerViewer {
    private PeerViewer() {}

    /**
     * Sentinel viewer for a console toggle: broadcast instead of whispering to one player. Public
     * because a caller reading {@link #viewer} has to tell "narrating to everyone" from "narrating
     * to that player".
     */
    public static final UUID EVERYONE = new UUID(0L, 0L);

    /** Watched person → who gets the narration (a player, or {@link #EVERYONE}). */
    private static final Map<MinecraftServer, Map<PersonId, UUID>> WATCHERS = new HashMap<>();

    /** Call once from mod init. */
    public static void init() {
        ServerLifecycleEvents.SERVER_STOPPING.register(WATCHERS::remove);
    }

    /** Starts narrating this person's peer events; a null viewer means console = everyone. */
    public static void watch(MinecraftServer server, PersonId person, @Nullable UUID viewer) {
        WATCHERS.computeIfAbsent(server, s -> new HashMap<>())
                .put(person, viewer == null ? EVERYONE : viewer);
    }

    /**
     * Who this person's narration currently goes to — a player's UUID, {@link #EVERYONE} for a
     * console toggle, or {@code null} when she isn't being narrated at all. The read side the
     * status readout of {@code /autarkia peers view} prints.
     */
    public static @Nullable UUID viewer(MinecraftServer server, PersonId person) {
        Map<PersonId, UUID> watched = WATCHERS.get(server);
        return watched == null ? null : watched.get(person);
    }

    /** Stops narrating; false when the person wasn't being watched. */
    public static boolean unwatch(MinecraftServer server, PersonId person) {
        Map<PersonId, UUID> watched = WATCHERS.get(server);
        return watched != null && watched.remove(person) != null;
    }

    /** A peer event from a possibly-watched person — narrate it to whoever toggled the view. */
    static void onEvent(MinecraftServer server, PersonId person, String personName,
                        String pronoun, PeerEvent event) {
        Map<PersonId, UUID> watched = WATCHERS.get(server);
        UUID viewer = watched == null ? null : watched.get(person);
        if (viewer == null) {
            return;
        }
        Component line = line(personName, pronoun, event);
        if (EVERYONE.equals(viewer)) {
            server.getPlayerList().broadcastSystemMessage(line, false);
            return;
        }
        ServerPlayer player = server.getPlayerList().getPlayer(viewer);
        if (player != null) {
            player.sendSystemMessage(line);
        }
    }

    private static Component line(String personName, String pronoun, PeerEvent event) {
        Peer peer = event.peer();
        String detail = peer.tell(pronoun)
                + (peer.awareness() == Peer.Awareness.SEEN
                        ? "" : " [" + peer.awareness().name().toLowerCase(Locale.ROOT) + "]");
        return switch (event.type()) {
            case SPOTTED -> Component.literal(
                            "[" + personName + "] spotted " + peer.knownAs() + " — " + detail)
                    .withStyle(ChatFormatting.GREEN);
            case LOST -> Component.literal(
                            "[" + personName + "] lost track of " + peer.knownAs())
                    .withStyle(ChatFormatting.RED);
            case READING_CHANGED -> Component.literal(
                            "[" + personName + "] " + peer.knownAs() + " now " + detail)
                    .withStyle(ChatFormatting.YELLOW);
            case RECOGNIZED -> Component.literal(
                            "[" + personName + "] recognized " + peer.name()
                                    + " — the someone she'd been hearing, now " + detail)
                    .withStyle(ChatFormatting.AQUA);
        };
    }
}
