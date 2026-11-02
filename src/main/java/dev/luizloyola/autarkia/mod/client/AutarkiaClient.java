package dev.luizloyola.autarkia.mod.client;

import dev.luizloyola.autarkia.core.tree.Pois;
import dev.luizloyola.anima.mod.client.DebugViewRenderer;
import dev.luizloyola.autarkia.compat.client.ClientRegistration;
import dev.luizloyola.autarkia.compat.client.inv.PersonInventoryScreen;
import dev.luizloyola.autarkia.mod.client.entity.ClientPerson;
import dev.luizloyola.autarkia.mod.client.render.PersonRenderer;
import dev.luizloyola.autarkia.mod.entity.ModEntities;
import dev.luizloyola.autarkia.mod.inv.ModMenus;
import net.fabricmc.api.ClientModInitializer;

public class AutarkiaClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientPerson.install();
        ClientRegistration.entityRenderer(ModEntities.PERSON, PersonRenderer::new);
        ClientRegistration.menuScreen(ModMenus.PERSON_INVENTORY, PersonInventoryScreen::new);
        // Anima's client entrypoint installs the contact cache, the glow sync and the debug view.
        // Here: the renderer, the inventory screen, the outline through Person's own render flag,
        // and the colours our POI kinds are drawn in.
        DebugGlow.install();
        DebugViewRenderer.kindColor(Pois.TREE, 0xFF3BA55D);
        DebugViewRenderer.kindColor(Pois.WATER, 0xFF3B7FA5);
    }
}
