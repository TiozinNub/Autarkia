package dev.luizloyola.autarkia.mod.brain;

import dev.luizloyola.autarkia.core.brain.knowledge.KnowledgeRegistry;
import java.util.HashMap;
import java.util.Map;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;

/**
 * The {@code mod} home of every person's knowledge: one pure {@link KnowledgeRegistry} per running
 * server — the {@link dev.luizloyola.autarkia.mod.log.Journals} shape (server-scoped holder over an
 * identity-keyed core store, server-thread only, removed on stop).
 *
 * <p><b>Transient TODAY, durable at ladder step 5</b>, when {@code PersonId}-keyed SavedData
 * attaches here; until then a restart starts every person ignorant.
 */
public final class Knowledges {
    private Knowledges() {}

    /** One registry per live server; removed on stop. Server-thread only. */
    private static final Map<MinecraftServer, KnowledgeRegistry> REGISTRIES = new HashMap<>();

    /** Call once from mod init: ties the per-server registries to the lifecycle. */
    public static void init() {
        ServerLifecycleEvents.SERVER_STOPPING.register(REGISTRIES::remove);
    }

    public static KnowledgeRegistry of(MinecraftServer server) {
        return REGISTRIES.computeIfAbsent(server, s -> new KnowledgeRegistry());
    }
}
