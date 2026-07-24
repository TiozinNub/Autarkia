package dev.luizloyola.autarkia.mod.brain;

import dev.luizloyola.autarkia.core.brain.board.SiteClaims;
import java.util.HashMap;
import java.util.Map;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;

/**
 * The {@code mod} home of the shared work-site claims: one pure {@link SiteClaims} per running
 * server, handed to every {@code BrainDriver} as its person-bound view. Server-scoped, transient,
 * rebuilt each boot — claims are coordination state, not memory, and the first heartbeats rebuild
 * the truth.
 */
public final class Claims {
    private Claims() {}

    /** One registry per live server; removed on stop. Server-thread only, like Journals. */
    private static final Map<MinecraftServer, SiteClaims> BY_SERVER = new HashMap<>();

    /** Call once from mod init: ties the per-server registries to the lifecycle. */
    public static void init() {
        ServerLifecycleEvents.SERVER_STOPPING.register(BY_SERVER::remove);
    }

    public static SiteClaims of(MinecraftServer server) {
        return BY_SERVER.computeIfAbsent(server, s -> new SiteClaims());
    }
}
