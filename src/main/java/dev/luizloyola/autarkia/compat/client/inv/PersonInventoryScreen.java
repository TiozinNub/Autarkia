package dev.luizloyola.autarkia.compat.client.inv;

import dev.luizloyola.autarkia.mod.inv.PersonInventoryMenu;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
//? if >=26.1 {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?} else {
/*import net.minecraft.client.gui.GuiGraphics;
*///?}
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;

/**
 * The screen for {@link PersonInventoryMenu}: a plain container view whose only custom bit is the
 * background texture and the inset paper-doll. It lives in {@code compat} rather than {@code mod}
 * because it is inherently version-specific — an {@link AbstractContainerScreen} subclass whose
 * constructor arity <em>and</em> background hook differ across MC versions (26.1 draws through a
 * retained-mode {@code GuiGraphicsExtractor}; older targets through immediate-mode {@code GuiGraphics}).
 * Slots/labels/items are drawn by {@link AbstractContainerScreen} defaults on every version.
 */
@Environment(EnvType.CLIENT)
public class PersonInventoryScreen extends AbstractContainerScreen<PersonInventoryMenu> {
    private static final Identifier TEXTURE =
            Identifier.fromNamespaceAndPath("autarkia", "textures/gui/container/person_inventory.png");
    /** The texture is a 256×256 sheet; we draw its top-left {@code WIDTH×HEIGHT} panel. */
    private static final int TEX_SIZE = 256;

    public PersonInventoryScreen(PersonInventoryMenu menu, Inventory playerInv, Component title) {
        //? if >=26.1 {
        super(menu, playerInv, title, PersonInventoryMenu.WIDTH, PersonInventoryMenu.HEIGHT);
        //?} else {
        /*super(menu, playerInv, title);
        this.imageWidth = PersonInventoryMenu.WIDTH;
        this.imageHeight = PersonInventoryMenu.HEIGHT;
        *///?}
        // "Inventory" label above the player's own grid (which starts at y=174).
        this.inventoryLabelY = this.imageHeight - 94;
        // The Person's name, past the right edge of the offhand slot (item x=77 + 16px cell ≈ 94)
        // plus padding, so a long name doesn't clip into it.
        this.titleLabelX = 98;
        this.titleLabelY = 12;
    }

    //? if >=26.1 {
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
    //?} else {
    /*@Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int x = (this.width - this.imageWidth) / 2;
        int y = (this.height - this.imageHeight) / 2;
        graphics.blit(RenderPipelines.GUI_TEXTURED, TEXTURE, x, y, 0.0F, 0.0F,
                this.imageWidth, this.imageHeight, TEX_SIZE, TEX_SIZE);
        // Paper doll: render this Person in the inset (the black rect), following the mouse — exactly
        // as the vanilla inventory renders the player. The offhand slot draws on top afterwards.
        if (this.minecraft != null && this.minecraft.level != null
                && this.minecraft.level.getEntity(getMenu().personEntityId()) instanceof LivingEntity person) {
            InventoryScreen.renderEntityInInventoryFollowsMouse(
                    graphics, x + 26, y + 8, x + 74, y + 77, 30, 0.0625F,
                    (float) mouseX, (float) mouseY, person);
        }
    }
    *///?}
}
