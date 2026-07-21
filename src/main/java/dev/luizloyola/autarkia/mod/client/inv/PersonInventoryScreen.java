package dev.luizloyola.autarkia.mod.client.inv;

import dev.luizloyola.autarkia.mod.inv.PersonInventoryMenu;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;

/**
 * The screen for {@link PersonInventoryMenu}: a plain container view whose only custom bit is the
 * background texture (26.1 screens are retained-mode — we override {@link #extractBackground} and
 * blit our panel; slots/labels/items are drawn by {@link AbstractContainerScreen} defaults).
 */
@Environment(EnvType.CLIENT)
public class PersonInventoryScreen extends AbstractContainerScreen<PersonInventoryMenu> {
    private static final Identifier TEXTURE =
            Identifier.fromNamespaceAndPath("autarkia", "textures/gui/container/person_inventory.png");

    public PersonInventoryScreen(PersonInventoryMenu menu, Inventory playerInv, Component title) {
        super(menu, playerInv, title, PersonInventoryMenu.WIDTH, PersonInventoryMenu.HEIGHT);
        // "Inventory" label just above the player's own grid.
        this.inventoryLabelY = this.imageHeight - 94;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
        int x = (this.width - this.imageWidth) / 2;
        int y = (this.height - this.imageHeight) / 2;
        extractor.blit(RenderPipelines.GUI_TEXTURED, TEXTURE, x, y, 0.0F, 0.0F,
                this.imageWidth, this.imageHeight, this.imageWidth, this.imageHeight);
    }
}
