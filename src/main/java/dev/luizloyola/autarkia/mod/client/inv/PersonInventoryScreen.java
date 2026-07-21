package dev.luizloyola.autarkia.mod.client.inv;

import dev.luizloyola.autarkia.mod.inv.PersonInventoryMenu;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.LivingEntity;
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
    /** The texture is a 256×256 sheet; we draw its top-left {@code WIDTH×HEIGHT} panel. */
    private static final int TEX_SIZE = 256;

    public PersonInventoryScreen(PersonInventoryMenu menu, Inventory playerInv, Component title) {
        super(menu, playerInv, title, PersonInventoryMenu.WIDTH, PersonInventoryMenu.HEIGHT);
        // "Inventory" label above the player's own grid (which starts at y=174).
        this.inventoryLabelY = this.imageHeight - 94;
        // The Person's name in the empty panel beside the paper-doll (top-right of the top section).
        this.titleLabelX = 78;
        this.titleLabelY = 12;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
        int x = (this.width - this.imageWidth) / 2;
        int y = (this.height - this.imageHeight) / 2;
        extractor.blit(RenderPipelines.GUI_TEXTURED, TEXTURE, x, y, 0.0F, 0.0F,
                this.imageWidth, this.imageHeight, TEX_SIZE, TEX_SIZE);
        // Paper doll: This Person in the inset, following the mouse. The offhand slot draws on top
        // afterwards.
        if (this.minecraft != null && this.minecraft.level != null
                && this.minecraft.level.getEntity(getMenu().personEntityId()) instanceof LivingEntity person) {
            InventoryScreen.extractEntityInInventoryFollowsMouse(
                    extractor, x + 26, y + 8, x + 74, y + 77, 30, 0.0625F,
                    (float) mouseX, (float) mouseY, person);
        }
    }
}
