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
import java.util.function.IntFunction;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;

/**
 * The screen for {@link PersonInventoryMenu}: background texture, inset paper-doll, armor / health /
 * hunger rows beside the doll, and the HUD's selection frame around the hand — the hotbar slot the
 * Person is holding from. In {@code compat} because an {@link AbstractContainerScreen} subclass's
 * constructor arity <em>and</em> background hook differ across MC versions (26.1 retained-mode
 * {@code GuiGraphicsExtractor}, older immediate-mode {@code GuiGraphics}); slots, labels and items
 * come from the superclass everywhere.
 *
 * <p>Reuses the vanilla HUD <em>sprites</em> but not its renderer — {@code Gui}'s heart/armor/food
 * draws are private and welded to the live HUD — so the static read (full/half/empty, effect tints)
 * is reproduced and the animation (damage blink, regen bounce) is not.
 */
@Environment(EnvType.CLIENT)
public class PersonInventoryScreen extends AbstractContainerScreen<PersonInventoryMenu> {
    private static final Identifier TEXTURE =
            Identifier.fromNamespaceAndPath("autarkia", "textures/gui/container/person_inventory.png");
    /** The texture is a 256×256 sheet; we draw its top-left {@code WIDTH×HEIGHT} panel. */
    private static final int TEX_SIZE = 256;

    // Vanilla HUD sprites. Hearts carry the effect tints (poison/wither/frozen) and drumsticks the
    // hunger tint; the empty heart is the plain container on every tint.
    private static final Identifier ARMOR_EMPTY = Identifier.withDefaultNamespace("hud/armor_empty");
    private static final Identifier ARMOR_HALF = Identifier.withDefaultNamespace("hud/armor_half");
    private static final Identifier ARMOR_FULL = Identifier.withDefaultNamespace("hud/armor_full");
    private static final Identifier HEART_CONTAINER = Identifier.withDefaultNamespace("hud/heart/container");
    private static final Identifier[] HEARTS_NORMAL = {
            Identifier.withDefaultNamespace("hud/heart/full"), Identifier.withDefaultNamespace("hud/heart/half")};
    private static final Identifier[] HEARTS_POISON = {
            Identifier.withDefaultNamespace("hud/heart/poisoned_full"), Identifier.withDefaultNamespace("hud/heart/poisoned_half")};
    private static final Identifier[] HEARTS_WITHER = {
            Identifier.withDefaultNamespace("hud/heart/withered_full"), Identifier.withDefaultNamespace("hud/heart/withered_half")};
    private static final Identifier[] HEARTS_FROZEN = {
            Identifier.withDefaultNamespace("hud/heart/frozen_full"), Identifier.withDefaultNamespace("hud/heart/frozen_half")};
    private static final Identifier[] FOOD_NORMAL = {Identifier.withDefaultNamespace("hud/food_full"),
            Identifier.withDefaultNamespace("hud/food_half"), Identifier.withDefaultNamespace("hud/food_empty")};
    private static final Identifier[] FOOD_HUNGER = {Identifier.withDefaultNamespace("hud/food_full_hunger"),
            Identifier.withDefaultNamespace("hud/food_half_hunger"), Identifier.withDefaultNamespace("hud/food_empty_hunger")};
    /** The HUD's hotbar selection frame, here marking the Person's selected slot. Its native 24×23. */
    private static final Identifier HOTBAR_SELECTION = Identifier.withDefaultNamespace("hud/hotbar_selection");
    private static final int SELECTION_W = 24;
    private static final int SELECTION_H = 23;
    /** Overhang of the frame past the 16×16 item box, top and left — the HUD's own spacing. */
    private static final int SELECTION_INSET = 4;

    // Armor, health, hunger rows right of the paper-doll. Each icon is 9px on an 8px pitch (1px
    // overlap, like the HUD): 10 × 8 + 1 = 81px, ending ~x+160, clear of the 176-wide panel, and
    // the rows fit between the name (y=12) and the offhand slot (y=62).
    private static final int VITAL_ICONS = 10;
    private static final int VITAL_ICON = 9;
    private static final int VITAL_PITCH = 8;
    private static final int VITALS_X = 79;
    private static final int ARMOR_Y = 25;
    private static final int HEALTH_Y = 36;
    private static final int HUNGER_Y = 47;

    /** The armor foreground for the {@code index}-th slot ({@code armor} points, 2 each), or null if empty. */
    private static Identifier armorForeground(int index, int armor) {
        int base = index * 2;
        if (armor >= base + 2) return ARMOR_FULL;
        if (armor >= base + 1) return ARMOR_HALF;
        return null;
    }

    /** The {full, half} heart pair for {@code entity}'s effects — poison/wither/frozen tint, like the HUD. */
    private static Identifier[] heartVariant(LivingEntity entity) {
        if (entity.hasEffect(MobEffects.POISON)) return HEARTS_POISON;
        if (entity.hasEffect(MobEffects.WITHER)) return HEARTS_WITHER;
        if (entity.isFullyFrozen()) return HEARTS_FROZEN;
        return HEARTS_NORMAL;
    }

    /** The heart foreground for the {@code index}-th heart ({@code variant} full/half), or null if empty. */
    private static Identifier heartForeground(int index, float health, Identifier[] variant) {
        float base = index * 2.0F;
        if (health >= base + 2.0F) return variant[0];
        if (health >= base + 1.0F) return variant[1];
        return null;
    }

    /** The food foreground for the {@code index}-th drumstick ({@code variant} full/half), or null if empty. */
    private static Identifier foodForeground(int index, int food, Identifier[] variant) {
        int base = index * 2;
        if (food >= base + 2) return variant[0];
        if (food >= base + 1) return variant[1];
        return null;
    }

    /** A version-neutral sprite blit — the only per-MC-version bit of the stat rows and the hand frame. */
    @FunctionalInterface
    private interface SpriteBlitter {
        void blit(Identifier sprite, int x, int y, int width, int height);
    }

    /**
     * Frames the Person's selected hotbar slot with the vanilla HUD's own selection sprite; the item
     * is drawn over the frame's hollow middle afterwards by {@link AbstractContainerScreen}, as on
     * the HUD.
     *
     * <p>The sprite is cut for the HUD's 20px pitch, not the 18px inventory grid, so it is placed by
     * the item box: 4px of overhang left and top of the 16×16 item, lapping ~3px onto the
     * neighbouring cells' borders exactly as on the HUD.
     */
    private void drawHandFrame(SpriteBlitter blit, int originX, int originY) {
        int slot = getMenu().selectedSlot();
        int itemX = originX + PersonInventoryMenu.HOTBAR_X + slot * PersonInventoryMenu.SLOT_PITCH;
        int itemY = originY + PersonInventoryMenu.HOTBAR_Y;
        blit.blit(HOTBAR_SELECTION, itemX - SELECTION_INSET, itemY - SELECTION_INSET,
                SELECTION_W, SELECTION_H);
    }

    /**
     * The armor / health / hunger rows at panel origin {@code (originX, originY)}: health off the
     * entity, hunger off the menu's food slot, hearts and drumsticks effect-tinted.
     */
    private void drawVitals(SpriteBlitter blit, int originX, int originY, LivingEntity person) {
        int armor = person.getArmorValue();
        drawRow(blit, originX, originY + ARMOR_Y, VITAL_ICONS, ARMOR_EMPTY, i -> armorForeground(i, armor));

        float health = person.getHealth();
        int hearts = Math.min(VITAL_ICONS, (int) Math.ceil(person.getMaxHealth() / 2.0F));
        Identifier[] heartVariant = heartVariant(person);
        drawRow(blit, originX, originY + HEALTH_Y, hearts, HEART_CONTAINER,
                i -> heartForeground(i, health, heartVariant));

        Identifier[] foodVariant = person.hasEffect(MobEffects.HUNGER) ? FOOD_HUNGER : FOOD_NORMAL;
        int food = getMenu().foodLevel();
        drawRow(blit, originX, originY + HUNGER_Y, VITAL_ICONS, foodVariant[2],
                i -> foodForeground(i, food, foodVariant));
    }

    /**
     * One row, the HUD's two passes: the empty {@code background} under every slot first, so half
     * icons composite over their own backing, then {@code foreground} on top wherever non-null.
     */
    private static void drawRow(SpriteBlitter blit, int originX, int rowY, int count,
                                Identifier background, IntFunction<Identifier> foreground) {
        for (int i = 0; i < count; i++) {
            int px = originX + VITALS_X + i * VITAL_PITCH;
            blit.blit(background, px, rowY, VITAL_ICON, VITAL_ICON);
            Identifier fg = foreground.apply(i);
            if (fg != null) blit.blit(fg, px, rowY, VITAL_ICON, VITAL_ICON);
        }
    }

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
        SpriteBlitter blit = (sprite, px, py, w, h) ->
                extractor.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, px, py, w, h);
        // The selected hotbar slot — synced, so it needs no entity and draws even if the doll can't.
        drawHandFrame(blit, x, y);
        // Paper doll: This Person in the inset, following the mouse. The offhand slot draws on top
        // afterwards.
        if (this.minecraft != null && this.minecraft.level != null
                && this.minecraft.level.getEntity(getMenu().personEntityId()) instanceof LivingEntity person) {
            InventoryScreen.extractEntityInInventoryFollowsMouse(
                    extractor, x + 26, y + 8, x + 74, y + 77, 30, 0.0625F,
                    (float) mouseX, (float) mouseY, person);
            // Armor / health / hunger, stacked vanilla HUD rows in the empty panel beside the doll.
            drawVitals(blit, x, y, person);
        }
    }
    //?} else {
    /*@Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int x = (this.width - this.imageWidth) / 2;
        int y = (this.height - this.imageHeight) / 2;
        graphics.blit(RenderPipelines.GUI_TEXTURED, TEXTURE, x, y, 0.0F, 0.0F,
                this.imageWidth, this.imageHeight, TEX_SIZE, TEX_SIZE);
        SpriteBlitter blit = (sprite, px, py, w, h) ->
                graphics.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, px, py, w, h);
        // The selected hotbar slot — synced, so it needs no entity and draws even if the doll can't.
        drawHandFrame(blit, x, y);
        // Paper doll: render this Person in the inset (the black rect), following the mouse — exactly
        // as the vanilla inventory renders the player. The offhand slot draws on top afterwards.
        if (this.minecraft != null && this.minecraft.level != null
                && this.minecraft.level.getEntity(getMenu().personEntityId()) instanceof LivingEntity person) {
            InventoryScreen.renderEntityInInventoryFollowsMouse(
                    graphics, x + 26, y + 8, x + 74, y + 77, 30, 0.0625F,
                    (float) mouseX, (float) mouseY, person);
            // Armor / health / hunger, stacked vanilla HUD rows in the empty panel beside the doll.
            drawVitals(blit, x, y, person);
        }
    }
    *///?}
}
