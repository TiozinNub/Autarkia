package dev.luizloyola.autarkia.mod.client;

import dev.luizloyola.anima.mod.command.AgentSelection;
import dev.luizloyola.anima.mod.client.DebugGlowClient;
import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.autarkia.mod.entity.Person;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;

/**
 * Client-only debug aid: the selected {@link Person} renders with a black glowing outline (see
 * {@link Person#getTeamColor()}). The selection is the player's server-side slot
 * ({@code AgentSelection}) mirrored by {@link DebugGlowClient}, so the outline follows the debug
 * wand and {@code /autarkia select} alike, whatever the player is holding. Purely local: it sets
 * each rendered person's transient forced-glow flag ({@link Person#setForcedGlow}), ORed into
 * {@code isCurrentlyGlowing()}.
 */
@Environment(EnvType.CLIENT)
public final class DebugGlow {
    private DebugGlow() {}

    public static void install() {
        ClientTickEvents.END_CLIENT_TICK.register(DebugGlow::tick);
    }

    private static void tick(Minecraft client) {
        ClientLevel level = client.level;
        if (level == null) {
            return;
        }
        // Re-evaluated every tick over every rendered person, so the outline follows the selection
        // and clears the instant it is dropped.
        //
        // Autarkia's, not Anima's: Who is selected lives in AgentSelection, what a selected body
        // LOOKS like is the consumer's — a pets mod would highlight a wolf its own way.
        AgentId selected = DebugGlowClient.get();
        for (Entity entity : level.entitiesForRendering()) {
            if (entity instanceof Person person) {
                person.setForcedGlow(selected != null && selected.equals(person.getAgentId()));
            }
        }
    }
}
