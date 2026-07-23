package dev.luizloyola.autarkia.mod.client;

import dev.luizloyola.autarkia.core.person.PersonId;
import dev.luizloyola.autarkia.mod.entity.Person;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;

/**
 * Client-only debug aid: the selected {@link Person} renders with a flashing outline
 * (black↔magenta once a second — see {@link Person#getTeamColor()}). The selection is the player's
 * server-side pin, mirrored by {@link DebugGlowClient}, so the outline follows the debug wand and
 * {@code /autarkia select} alike, whatever the player holds. Purely local: it sets the transient
 * {@link Person#setForcedGlow} flag, which the entity ORs into {@code isCurrentlyGlowing()}.
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
        // Re-evaluated every tick, so the outline clears itself the instant the pin is dropped.
        PersonId selected = DebugGlowClient.get();
        for (Entity entity : level.entitiesForRendering()) {
            if (entity instanceof Person person) {
                person.setForcedGlow(selected != null && selected.equals(person.getPersonId()));
            }
        }
    }
}
