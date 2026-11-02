package dev.luizloyola.autarkia.compat.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.MenuAccess;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.EntityRenderers;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
//? if >=26.4-alpha {
/*import net.minecraft.client.core.registries.BuiltInClientRegistries;
*///?}

/**
 * Vanilla's own renderer and screen tables, which Fabric's transitive access wideners open on
 * every target. 26.4 made them built-in client registries that the caller names; Fabric holds
 * them open until client entrypoints have run.
 */
@Environment(EnvType.CLIENT)
public final class ClientRegistration {

    private ClientRegistration() {
    }

    public static <T extends Entity> void entityRenderer(EntityType<? extends T> type,
                                                         EntityRendererProvider<T> provider) {
        //? if >=26.4-alpha {
        /*EntityRenderers.register(BuiltInClientRegistries.ENTITY_RENDERER, type, provider);
        *///?} else {
        EntityRenderers.register(type, provider);
        //?}
    }

    public static <M extends AbstractContainerMenu, U extends Screen & MenuAccess<M>> void menuScreen(
            MenuType<? extends M> type, MenuScreens.ScreenConstructor<M, U> constructor) {
        //? if >=26.4-alpha {
        /*MenuScreens.register(BuiltInClientRegistries.MENU_SCREEN, type, constructor);
        *///?} else {
        MenuScreens.register(type, constructor);
        //?}
    }
}
