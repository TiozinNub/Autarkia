package dev.luizloyola.autarkia.mod.debug;

import dev.luizloyola.autarkia.core.brain.knowledge.PersonKnowledge;
import dev.luizloyola.autarkia.core.brain.knowledge.PoiKind;
import dev.luizloyola.autarkia.core.brain.knowledge.PoiMemory;
import dev.luizloyola.autarkia.core.brain.knowledge.Region;
import dev.luizloyola.autarkia.core.brain.sense.Peer;
import dev.luizloyola.autarkia.core.brain.sense.PeerSensorCore;
import dev.luizloyola.autarkia.core.brain.sense.Pos;
import dev.luizloyola.autarkia.core.nav.Path;
import dev.luizloyola.autarkia.core.nav.Waypoint;
import dev.luizloyola.autarkia.core.person.PersonId;
import dev.luizloyola.autarkia.mod.brain.Knowledges;
import dev.luizloyola.autarkia.mod.command.PersonSelection;
import dev.luizloyola.autarkia.mod.entity.Person;
import dev.luizloyola.autarkia.mod.entity.Persons;
import dev.luizloyola.autarkia.mod.nav.Navigator;
import dev.luizloyola.autarkia.mod.net.DebugViewPayload;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import org.jspecify.annotations.Nullable;

/**
 * The in-world debug view, server half: who is watching what, and the snapshot that feeds it.
 * Unlike the chat viewers this one DRAWS, and drawing happens on the client, so each watching
 * player gets one {@link DebugViewPayload} every {@link #SEND_INTERVAL_TICKS} carrying only what
 * the client cannot already see for itself.
 *
 * <p><b>The view follows the selection, not a separate target.</b> Layers are switched per PLAYER,
 * and the Person drawn is whoever that player has pinned in {@code PersonSelection} — the slot the
 * debug wand and {@code /autarkia select} already share.
 *
 * <p>A player whose layers are all off, or whose pin is empty or unloaded, gets exactly one
 * {@link DebugViewPayload#clear()} and then silence — without that edge the client would keep
 * redrawing its last snapshot forever. Transient debug state, one map per server, gone on stop.
 */
public final class DebugView {
    private DebugView() {}

    /**
     * Snapshot cadence, slower than a frame: the client interpolates the Person's
     * position from the local entity, so only the slow-changing part travels (the path, the
     * beliefs). Four ticks reads as instant and costs nothing.
     */
    private static final int SEND_INTERVAL_TICKS = 4;

    /**
     * How long a belief may go unconfirmed before the view draws it as a GHOST — five minutes of
     * game time, long enough that walking a lap around a grove doesn't grey it out. A rendering
     * threshold only: the knowledge store has no such cliff, and the brain prices staleness on a
     * continuous curve.
     */
    private static final long STALE_AFTER_TICKS = 6000L;

    /** Watching player → the layers they have switched on. An empty set is removed, not kept. */
    private static final Map<MinecraftServer, Map<UUID, EnumSet<DebugLayer>>> WATCHERS =
            new HashMap<>();

    /** Players owed one final clear because their view just went dark. */
    private static final Map<MinecraftServer, Set<UUID>> PENDING_CLEAR = new HashMap<>();

    /** Call once from common mod init — the payload type must be registered on both sides. */
    public static void init() {
        PayloadTypeRegistry.clientboundPlay().register(DebugViewPayload.TYPE, DebugViewPayload.CODEC);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            WATCHERS.remove(server);
            PENDING_CLEAR.remove(server);
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % SEND_INTERVAL_TICKS == 0) {
                broadcast(server);
            }
        });
    }

    /** The layers this player currently has on — never null, possibly empty. */
    public static EnumSet<DebugLayer> layers(MinecraftServer server, UUID player) {
        Map<UUID, EnumSet<DebugLayer>> watched = WATCHERS.get(server);
        EnumSet<DebugLayer> on = watched == null ? null : watched.get(player);
        return on == null ? EnumSet.noneOf(DebugLayer.class) : EnumSet.copyOf(on);
    }

    /** Switches one layer on or off for this player; returns the resulting layer set. */
    public static EnumSet<DebugLayer> set(
            MinecraftServer server, UUID player, DebugLayer layer, boolean on) {
        EnumSet<DebugLayer> next = layers(server, player);
        if (on) {
            next.add(layer);
        } else {
            next.remove(layer);
        }
        return replace(server, player, next);
    }

    /**
     * Replaces this player's whole layer set — the debug wand's one-at-a-time cycle, which must
     * REPLACE rather than accumulate (an empty set is the wand's "off" rung).
     */
    public static EnumSet<DebugLayer> replace(
            MinecraftServer server, UUID player, Set<DebugLayer> layers) {
        Map<UUID, EnumSet<DebugLayer>> watched =
                WATCHERS.computeIfAbsent(server, s -> new HashMap<>());
        if (layers.isEmpty()) {
            // Going dark owes the client one clear; leaving the entry in the map would keep
            // sending empty snapshots forever instead.
            if (watched.remove(player) != null) {
                PENDING_CLEAR.computeIfAbsent(server, s -> new HashSet<>()).add(player);
            }
            return EnumSet.noneOf(DebugLayer.class);
        }
        EnumSet<DebugLayer> copy = EnumSet.copyOf(layers);
        watched.put(player, copy);
        return EnumSet.copyOf(copy);
    }

    /** Switches everything off for this player; true when anything was on. */
    public static boolean clear(MinecraftServer server, UUID player) {
        boolean had = !layers(server, player).isEmpty();
        replace(server, player, EnumSet.noneOf(DebugLayer.class));
        return had;
    }

    /** One cadence tick: a fresh snapshot to every watcher, one last clear to anyone who stopped. */
    private static void broadcast(MinecraftServer server) {
        Set<UUID> clearing = PENDING_CLEAR.remove(server);
        if (clearing != null) {
            for (UUID id : clearing) {
                send(server.getPlayerList().getPlayer(id), DebugViewPayload.clear());
            }
        }
        Map<UUID, EnumSet<DebugLayer>> watched = WATCHERS.get(server);
        if (watched == null || watched.isEmpty()) {
            return;
        }
        for (Map.Entry<UUID, EnumSet<DebugLayer>> entry : watched.entrySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player != null) {
                send(player, snapshot(server, player, entry.getValue()));
            }
        }
    }

    /** Skips clients that never registered the channel (vanilla clients, the headless harness). */
    private static void send(@Nullable ServerPlayer player, DebugViewPayload payload) {
        if (player != null && ServerPlayNetworking.canSend(player, DebugViewPayload.TYPE)) {
            ServerPlayNetworking.send(player, payload);
        }
    }

    /**
     * This player's frame: their pinned Person read through the requested layers. An unresolvable
     * pin yields the clear snapshot rather than an error — the selection going away should stop the
     * drawing, not spam the player.
     */
    private static DebugViewPayload snapshot(
            MinecraftServer server, ServerPlayer player, EnumSet<DebugLayer> layers) {
        PersonId id = PersonSelection.pinned(player).orElse(null);
        Person person = id == null ? null : Persons.findLoaded(server, id);
        if (person == null) {
            return DebugViewPayload.clear();
        }
        Navigator navigator = person.navigator();
        boolean wantsPath = layers.contains(DebugLayer.PATH);
        Path path = wantsPath ? navigator.path() : null;

        List<DebugViewPayload.Step> steps = new ArrayList<>();
        if (path != null) {
            for (Waypoint waypoint : path.waypoints()) {
                steps.add(new DebugViewPayload.Step(
                        new BlockPos(waypoint.x(), waypoint.y(), waypoint.z()),
                        waypoint.move().ordinal()));
            }
        }
        Optional<BlockPos> goal = wantsPath
                ? Optional.ofNullable(navigator.goal())
                : Optional.empty();

        return new DebugViewPayload(
                person.getId(),
                DebugLayer.mask(layers),
                steps,
                wantsPath ? navigator.pathIndex() : 0,
                goal,
                wantsPath ? navigator.describe() : "",
                layers.contains(DebugLayer.BRAIN) ? person.brain().describeLines() : List.of(),
                layers.contains(DebugLayer.MEMORY) ? beliefs(server, person) : List.of(),
                layers.contains(DebugLayer.PEERS) ? peers(person) : List.of(),
                PeerSensorCore.coneDegrees(),
                PeerSensorCore.radius());
    }

    /** Everything she remembers, of every kind, flattened with staleness resolved server-side. */
    private static List<DebugViewPayload.Belief> beliefs(MinecraftServer server, Person person) {
        PersonId id = person.getPersonId();
        if (id == null) {
            return List.of();
        }
        long now = person.level().getGameTime();
        PersonKnowledge knowledge = Knowledges.of(server).forPerson(id);
        List<DebugViewPayload.Belief> out = new ArrayList<>();
        for (PoiKind kind : PoiKind.values()) {
            for (PoiMemory memory : knowledge.all(kind)) {
                Region bounds = memory.bounds();
                out.add(new DebugViewPayload.Belief(
                        memory.kind().ordinal(),
                        cell(memory.anchor()),
                        cell(bounds.min()),
                        cell(bounds.max()),
                        memory.age(now) > STALE_AFTER_TICKS));
            }
        }
        return out;
    }

    /**
     * Her live peer reading — through the BRAIN's own eyes ({@code percepts()}), not a fresh
     * sensor: the cache carries the movement history and the linger window, and a throwaway scan
     * would report everyone as freshly seen and standing still.
     */
    private static List<DebugViewPayload.PeerMark> peers(Person person) {
        List<DebugViewPayload.PeerMark> out = new ArrayList<>();
        for (Peer peer : person.brain().percepts().peers()) {
            out.add(new DebugViewPayload.PeerMark(
                    peer.name(), cell(peer.pos()), bodyId(person, peer),
                    peer.awareness().ordinal(), peer.activity().ordinal()));
        }
        return out;
    }

    /**
     * The entity to interpolate a peer's mark from, or {@link DebugViewPayload.PeerMark#NO_BODY}.
     *
     * <p>SEEN only, and the gate is here rather than on the client so no client can follow a body
     * she has lost track of. A seen peer's cell is a live sample taken on the sensor's attention
     * cadence, so following the body draws the same truth more smoothly; for HEARD and REMEMBERED
     * the believed cell is the fact.
     */
    private static int bodyId(Person person, Peer peer) {
        if (peer.awareness() != Peer.Awareness.SEEN) {
            return DebugViewPayload.PeerMark.NO_BODY;
        }
        LivingEntity body = person.peerSense().bodyOf(peer.id());
        return body == null ? DebugViewPayload.PeerMark.NO_BODY : body.getId();
    }

    private static BlockPos cell(Pos pos) {
        return new BlockPos(pos.x(), pos.y(), pos.z());
    }
}
