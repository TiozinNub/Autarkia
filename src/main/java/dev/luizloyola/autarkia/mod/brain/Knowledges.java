package dev.luizloyola.autarkia.mod.brain;

import dev.luizloyola.autarkia.core.brain.knowledge.KnowledgeRegistry;
import net.minecraft.server.MinecraftServer;

/**
 * The one accessor everything reads knowledge through — a thin name over {@link KnowledgeData} so
 * the sensor, the commands and the viewer never care where the registry lives. A restart hands
 * every person their memories back.
 */
public final class Knowledges {
    private Knowledges() {}

    /** This server's knowledge registry, backed by the overworld-attached {@link KnowledgeData}. */
    public static KnowledgeRegistry of(MinecraftServer server) {
        return KnowledgeData.get(server).registry();
    }
}
