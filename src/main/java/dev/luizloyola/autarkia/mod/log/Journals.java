package dev.luizloyola.autarkia.mod.log;

import dev.luizloyola.autarkia.core.log.JournalService;
import java.util.HashMap;
import java.util.Map;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;

/**
 * The {@code mod} home of the per-person debug log: one pure {@link JournalService} per running
 * server, given the two things a {@code core} service cannot have — a real game-time clock and a
 * place in the server lifecycle. Server-scoped and identity-keyed like {@code PersonDirectory},
 * transient and rebuilt each boot like {@code PathfinderService}.
 *
 * <p><b>Not persisted</b>: no {@code SavedData}, because the rings are ephemeral by design — the
 * durable archive is the per-person file {@link JournalFileSink} writes — so a fresh boot starts
 * with empty rings.
 *
 * <p>The clock is the overworld's game time
 * ({@link net.minecraft.server.level.ServerLevel#getGameTime()}), shared server-wide, so every
 * person's lines share one monotonic timeline. A periodic {@link JournalService#sweep()} enforces
 * the age bound; the per-person line cap enforces itself on every write.
 */
public final class Journals {
    private Journals() {}

    /** How often the age sweep runs — ~30s at 20 ticks/second. The line cap needs no cadence. */
    private static final int SWEEP_INTERVAL_TICKS = 600;

    /** One service per live server; removed on stop. Server-thread only, like the map it mirrors. */
    private static final Map<MinecraftServer, JournalService> SERVICES = new HashMap<>();

    /** The file sink attached to each server's service, so {@code SERVER_STOPPING} can flush + close it. */
    private static final Map<MinecraftServer, JournalFileSink> SINKS = new HashMap<>();

    /** Call once from mod init: ties the per-server services, their file sinks, and the age sweep to
     *  the lifecycle. */
    public static void init() {
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            SERVICES.remove(server);
            JournalFileSink sink = SINKS.remove(server);
            if (sink != null) {
                sink.close(); // final drain + close every per-person file
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % SWEEP_INTERVAL_TICKS == 0) {
                JournalService service = SERVICES.get(server);
                if (service != null) {
                    service.sweep();
                }
            }
        });
    }

    /**
     * This server's journal service, created on first use with the overworld game-time clock and its
     * per-person file sink. Server-thread only, so the plain get-then-put needs no locking.
     */
    public static JournalService of(MinecraftServer server) {
        JournalService existing = SERVICES.get(server);
        if (existing != null) {
            return existing;
        }
        JournalService service = new JournalService(server.overworld()::getGameTime);
        SERVICES.put(server, service);
        SINKS.put(server, JournalFileSink.attach(server, service));
        ThoughtBroadcast.attach(server, service); // the thinking-out-loud chat channel
        return service;
    }
}
