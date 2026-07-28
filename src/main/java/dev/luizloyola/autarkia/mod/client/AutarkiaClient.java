package dev.luizloyola.autarkia.mod.client;

import dev.luizloyola.autarkia.core.tree.Pois;
import dev.luizloyola.anima.mod.client.DebugViewRenderer;
import dev.luizloyola.autarkia.compat.client.inv.PersonInventoryScreen;
import dev.luizloyola.autarkia.mod.client.entity.ClientPerson;
import dev.luizloyola.autarkia.mod.client.render.PersonRenderer;
import dev.luizloyola.autarkia.mod.entity.ModEntities;
import dev.luizloyola.autarkia.mod.inv.ModMenus;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.renderer.entity.EntityRenderers;

public class AutarkiaClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientPerson.install();
        // Vanilla EntityRenderers.register is package-private, but Fabric's transitive
        // access-wideners module exposes it on every target (7.1.0 pre-26.1, 8.1.3 on
        // 26.1+), so we can call it directly instead of the deprecated Fabric helper.
        EntityRenderers.register(ModEntities.PERSON, PersonRenderer::new);
        // MenuScreens.register is package-private too — the same access-widener route.
        MenuScreens.register(ModMenus.PERSON_INVENTORY, PersonInventoryScreen::new);
        // Anima's client entrypoint installs the contact cache, the glow sync and the debug view.
        // Here: the renderer, the inventory screen, the outline through Person's own render flag,
        // and the colours our POI kinds are drawn in.
        DebugGlow.install();
        DebugViewRenderer.kindColor(Pois.TREE, 0xFF3BA55D);
        DebugViewRenderer.kindColor(Pois.WATER, 0xFF3B7FA5);
    }
}
