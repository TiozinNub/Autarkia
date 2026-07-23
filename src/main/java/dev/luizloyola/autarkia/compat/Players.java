package dev.luizloyola.autarkia.compat;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

/**
 * Player-facing actions whose Minecraft API drifts across versions — the version-neutral seam
 * {@code mod} tools use.
 */
public final class Players {
    private Players() {}

    /**
     * Shows {@code message} on the player's action bar (the overlay line above the hotbar). 26.1
     * exposes this as {@code sendOverlayMessage}; older targets fold it into
     * {@code displayClientMessage(component, actionBar=true)}.
     */
    public static void overlay(Player player, Component message) {
        //? if >=26.1 {
        player.sendOverlayMessage(message);
        //?} else {
        /*player.displayClientMessage(message, true);
        *///?}
    }
}
