package dev.luizloyola.autarkia.mod.brain;

import dev.luizloyola.autarkia.core.brain.knowledge.KnowledgeRegistry;
import dev.luizloyola.autarkia.core.brain.knowledge.PersonKnowledge;
import dev.luizloyola.autarkia.core.brain.knowledge.PoiKind;
import dev.luizloyola.autarkia.core.brain.knowledge.PoiMemory;
import dev.luizloyola.autarkia.core.brain.knowledge.Region;
import dev.luizloyola.autarkia.core.brain.knowledge.SenseEvent;
import dev.luizloyola.autarkia.core.brain.sense.Pos;
import dev.luizloyola.autarkia.core.person.PersonId;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * The POI viewer: a watched person's <em>beliefs</em> made visible — particles over every
 * remembered anchor and bounds corner, a chat line to the watcher on each discovery. It renders the
 * knowledge store, not the world: a ghost marker over a chopped tree she has not revisited is the
 * viewer working correctly.
 *
 * <p>Transport is {@link ServerLevel#sendParticles}, broadcast to trackers, zero custom networking.
 * All state is transient debug state, one watcher map per server.
 */
public final class KnowledgeViewer {
    private KnowledgeViewer() {}

    /** Particle cadence — every half second, slow enough to never matter, fast enough to read. */
    private static final int RENDER_INTERVAL_TICKS = 10;

    /** Watched person → the player who toggled the view (gets the discovery chat lines). */
    private static final Map<MinecraftServer, Map<PersonId, UUID>> WATCHERS = new HashMap<>();

    /** Call once from mod init. */
    public static void init() {
        ServerLifecycleEvents.SERVER_STOPPING.register(WATCHERS::remove);
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % RENDER_INTERVAL_TICKS == 0) {
                render(server);
            }
        });
    }

    /** Starts viewing this person's knowledge, routing discovery chat to {@code viewer}. */
    public static void watch(MinecraftServer server, PersonId person, UUID viewer) {
        WATCHERS.computeIfAbsent(server, s -> new HashMap<>()).put(person, viewer);
    }

    /** Stops viewing; false when the person wasn't being viewed. */
    public static boolean unwatch(MinecraftServer server, PersonId person) {
        Map<PersonId, UUID> watched = WATCHERS.get(server);
        return watched != null && watched.remove(person) != null;
    }

    /** A perception event for a possibly-watched person — chat it to whoever toggled the view. */
    static void onEvent(MinecraftServer server, PersonId person, String personName, SenseEvent event) {
        Map<PersonId, UUID> watched = WATCHERS.get(server);
        UUID viewer = watched == null ? null : watched.get(person);
        if (viewer == null) {
            return;
        }
        ServerPlayer player = server.getPlayerList().getPlayer(viewer);
        if (player == null) {
            return;
        }
        String verb;
        ChatFormatting color;
        switch (event.type()) {
            case NOTED -> {
                verb = "noticed ";
                color = ChatFormatting.GREEN;
            }
            case FORGOT -> {
                verb = "forgot ";
                color = ChatFormatting.RED;
            }
            case OVERLOOKED -> {
                verb = "overlooked (ray blocked) ";
                color = ChatFormatting.GRAY;
            }
            default -> {
                verb = "dismissed (not the thing) ";
                color = ChatFormatting.GRAY;
            }
        }
        player.sendSystemMessage(Component.literal(
                        "[" + personName + "] " + verb + PoiSensor.describe(event))
                .withStyle(color));
    }

    private static void render(MinecraftServer server) {
        Map<PersonId, UUID> watched = WATCHERS.get(server);
        if (watched == null || watched.isEmpty()) {
            return;
        }
        ServerLevel level = server.overworld(); // knowledge is overworld-scoped in v1
        KnowledgeRegistry registry = Knowledges.of(server);
        for (PersonId person : watched.keySet()) {
            PersonKnowledge knowledge = registry.forPerson(person);
            for (PoiKind kind : PoiKind.values()) {
                for (PoiMemory memory : knowledge.all(kind)) {
                    emit(level, memory);
                }
            }
        }
    }

    /** One belief: a rising column at the anchor, a dot on each bounds corner. */
    private static void emit(ServerLevel level, PoiMemory memory) {
        SimpleParticleType particle = memory.kind() == PoiKind.TREE
                ? ParticleTypes.HAPPY_VILLAGER
                : ParticleTypes.DRIPPING_WATER;
        Pos anchor = memory.anchor();
        for (int i = 0; i < 4; i++) {
            level.sendParticles(particle,
                    anchor.x() + 0.5, anchor.y() + 0.7 + i * 0.6, anchor.z() + 0.5, 1, 0, 0, 0, 0);
        }
        Region bounds = memory.bounds();
        for (int x : new int[]{bounds.min().x(), bounds.max().x()}) {
            for (int y : new int[]{bounds.min().y(), bounds.max().y()}) {
                for (int z : new int[]{bounds.min().z(), bounds.max().z()}) {
                    level.sendParticles(particle, x + 0.5, y + 0.5, z + 0.5, 1, 0, 0, 0, 0);
                }
            }
        }
    }
}
