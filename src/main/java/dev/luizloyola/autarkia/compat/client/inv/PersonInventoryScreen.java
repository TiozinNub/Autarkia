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
 * The screen for {@link PersonInventoryMenu}: background texture, inset paper-doll, and a mini
 * armor / health / hunger readout beside it; the rest is {@link AbstractContainerScreen} default.
 * In {@code compat} because constructor arity <em>and</em> background hook are version-specific
 * (26.1 retained-mode {@code GuiGraphicsExtractor}, older immediate-mode {@code GuiGraphics}).
 *
 * <p>The rows walk the vanilla HUD sprites directly — {@code Gui}'s draws are private and welded to
 * the live HUD — so the read is static: no damage blink, no regen bounce.
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

    /** A version-neutral 9×9 sprite blit at {@code (x, y)} — the only per-MC-version bit of the stat rows. */
    @FunctionalInterface
    private interface IconBlitter {
        void blit(Identifier sprite, int x, int y);
    }

    /**
     * The armor / health / hunger rows at panel origin {@code (originX, originY)}: health off the
     * entity, hunger off the menu's food slot, hearts and drumsticks effect-tinted.
     */
    private void drawVitals(IconBlitter blit, int originX, int originY, LivingEntity person) {
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
    private static void drawRow(IconBlitter blit, int originX, int rowY, int count,
                                Identifier background, IntFunction<Identifier> foreground) {
        for (int i = 0; i < count; i++) {
            int px = originX + VITALS_X + i * VITAL_PITCH;
            blit.blit(background, px, rowY);
            Identifier fg = foreground.apply(i);
            if (fg != null) blit.blit(fg, px, rowY);
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
        // Paper doll: This Person in the inset, following the mouse. The offhand slot draws on top
        // afterwards.
        if (this.minecraft != null && this.minecraft.level != null
                && this.minecraft.level.getEntity(getMenu().personEntityId()) instanceof LivingEntity person) {
            InventoryScreen.extractEntityInInventoryFollowsMouse(
                    extractor, x + 26, y + 8, x + 74, y + 77, 30, 0.0625F,
                    (float) mouseX, (float) mouseY, person);
            // Armor / health / hunger, stacked vanilla HUD rows in the empty panel beside the doll.
            drawVitals((sprite, px, py) ->
                    extractor.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, px, py, VITAL_ICON, VITAL_ICON),
                    x, y, person);
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
            // Armor / health / hunger, stacked vanilla HUD rows in the empty panel beside the doll.
            drawVitals((sprite, px, py) ->
                    graphics.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, px, py, VITAL_ICON, VITAL_ICON),
                    x, y, person);
        }
    }
    *///?}
}
