package dev.luizloyola.autarkia.mod.brain;

import dev.luizloyola.autarkia.core.brain.knowledge.KnowledgeRegistry;
import net.minecraft.server.MinecraftServer;

/**
 * The one accessor everything reads knowledge through, over the persisted {@link KnowledgeData}.
 * Durable since perception ladder step 5: a restart hands every person her memories back.
 */
public final class Knowledges {
    private Knowledges() {}

    /** This server's knowledge registry, backed by the overworld-attached {@link KnowledgeData}. */
    public static KnowledgeRegistry of(MinecraftServer server) {
        return KnowledgeData.get(server).registry();
    }
}
