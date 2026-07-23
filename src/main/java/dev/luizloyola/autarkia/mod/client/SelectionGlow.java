package dev.luizloyola.autarkia.mod.client;

import dev.luizloyola.autarkia.core.person.PersonId;
import dev.luizloyola.autarkia.mod.entity.Person;
import dev.luizloyola.autarkia.mod.item.ModComponents;
import dev.luizloyola.autarkia.mod.item.ModItems;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Client-only visual aid: while the player holds a debug wand in either hand, the {@link Person}
 * that stick has selected renders with a glowing outline. Purely local — it sets each rendered
 * person's transient forced-glow flag ({@link Person#setForcedGlow}), which the entity ORs into its
 * {@code isCurrentlyGlowing()}; nothing is sent to the server.
 */
@Environment(EnvType.CLIENT)
public final class DebugWandGlow {
    private DebugWandGlow() {}

    public static void install() {
        ClientTickEvents.END_CLIENT_TICK.register(DebugWandGlow::tick);
    }

    private static void tick(Minecraft client) {
        LocalPlayer player = client.player;
        ClientLevel level = client.level;
        if (player == null || level == null) {
            return;
        }
        // Re-evaluated every tick, so the outline follows hand and selection changes and clears
        // the instant the stick is put away.
        PersonId selected = heldSelection(player);
        for (Entity entity : level.entitiesForRendering()) {
            if (entity instanceof Person person) {
                person.setForcedGlow(selected != null && selected.equals(person.getPersonId()));
            }
        }
    }

    private static @Nullable PersonId heldSelection(LocalPlayer player) {
        PersonId main = selectionOf(player.getMainHandItem());
        return main != null ? main : selectionOf(player.getOffhandItem());
    }

    private static @Nullable PersonId selectionOf(ItemStack stack) {
        return stack.getItem() == ModItems.DEBUG_WAND ? stack.get(ModComponents.SELECTED_PERSON) : null;
    }
}
