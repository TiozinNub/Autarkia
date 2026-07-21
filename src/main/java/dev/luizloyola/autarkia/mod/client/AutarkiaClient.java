package dev.luizloyola.autarkia.mod.client;

import dev.luizloyola.autarkia.mod.client.entity.ClientPerson;
import dev.luizloyola.autarkia.mod.client.render.PersonRenderer;
import dev.luizloyola.autarkia.mod.entity.ModEntities;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;

public class AutarkiaClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientPerson.install();
        EntityRendererRegistry.register(ModEntities.PERSON, PersonRenderer::new);
    }
}
