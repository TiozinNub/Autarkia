package dev.luizloyola.autarkia.mod.inv;

import dev.luizloyola.autarkia.core.inv.ArmorType;
import dev.luizloyola.autarkia.core.inv.Inventory;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.equipment.Equippable;

/**
 * The menu behind the "open a Person's inventory" screen: the Person's 41 slots (9 hotbar + 27 main
 * as a 4×9 storage block, a 4-slot armor column, 1 offhand) plus the player's 36, shift-click routed
 * between them.
 *
 * <p>Server-side the backing {@link Container} is a live {@link PersonContainer}; client-side a
 * dummy {@link SimpleContainer} of the same size. Both sides must build the identical layout — the
 * menu framework's contract.
 *
 * <p>Slot indices: {@code 0..40} are the Person's ({@code slot i == core slot i}); {@code 41..76}
 * the player's (27 main then 9 hotbar).
 */
public final class PersonInventoryMenu extends AbstractContainerMenu {
    /** Window geometry (kept in lockstep with the background texture; see the texture generator). */
    public static final int WIDTH = 201;
    public static final int HEIGHT = 200;

    private static final int PERSON_SLOTS = Inventory.SIZE; // 41
    private static final int STORAGE_END = Inventory.ARMOR_START; // 36

    // Person storage block: 4 rows × 9, top-left of the item area.
    private static final int GRID_X = 34;
    private static final int GRID_Y = 18;
    // Armor column + offhand on the left.
    private static final int ARMOR_X = 8;
    private static final int ARMOR_Y = 18;
    private static final int OFFHAND_Y = 90;
    private static final int PINV_X = 34;
    private static final int PINV_MAIN_Y = 118;
    private static final int PINV_HOTBAR_Y = 176;

    private final Container personContainer;

    /** Client-side factory (via the {@code MenuType}): a dummy container vanilla will sync into. */
    public PersonInventoryMenu(int syncId, net.minecraft.world.entity.player.Inventory playerInv) {
        this(syncId, playerInv, new SimpleContainer(Inventory.SIZE));
    }

    /** Server-side: {@code personContainer} is the live {@link PersonContainer}. */
    public PersonInventoryMenu(int syncId, net.minecraft.world.entity.player.Inventory playerInv,
                               Container personContainer) {
        super(ModMenus.PERSON_INVENTORY, syncId);
        checkContainerSize(personContainer, Inventory.SIZE);
        this.personContainer = personContainer;

        // --- Person storage: core slots 0..35 as a 4×9 grid ---
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 9; col++) {
                int slot = row * 9 + col;
                addSlot(new Slot(personContainer, slot, GRID_X + col * 18, GRID_Y + row * 18));
            }
        }
        // --- Armor column: core 36..39 (HEAD..FEET), each gated to its piece ---
        addSlot(armorSlot(personContainer, armorIndex(ArmorType.HEAD), ARMOR_X, ARMOR_Y, EquipmentSlot.HEAD));
        addSlot(armorSlot(personContainer, armorIndex(ArmorType.CHEST), ARMOR_X, ARMOR_Y + 18, EquipmentSlot.CHEST));
        addSlot(armorSlot(personContainer, armorIndex(ArmorType.LEGS), ARMOR_X, ARMOR_Y + 36, EquipmentSlot.LEGS));
        addSlot(armorSlot(personContainer, armorIndex(ArmorType.FEET), ARMOR_X, ARMOR_Y + 54, EquipmentSlot.FEET));
        // --- Offhand: core 40 (accepts anything, like a player's offhand) ---
        addSlot(new Slot(personContainer, Inventory.OFFHAND_SLOT, ARMOR_X, OFFHAND_Y));

        // --- Player inventory: 27 main then 9 hotbar ---
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInv, 9 + row * 9 + col, PINV_X + col * 18, PINV_MAIN_Y + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInv, col, PINV_X + col * 18, PINV_HOTBAR_Y));
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (slot == null || !slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();
        if (index < PERSON_SLOTS) {
            // Person area -> player inventory
            if (!moveItemStackTo(stack, PERSON_SLOTS, PERSON_SLOTS + 36, true)) return ItemStack.EMPTY;
        } else {
            // Player inventory -> Person. Wearable gear tries the armor slots first (their mayPlace
            // gate picks the right one), then falls back to storage. [ARMOR_START, OFFHAND_SLOT)
            // excludes the offhand.
            if (!moveItemStackTo(stack, Inventory.ARMOR_START, Inventory.OFFHAND_SLOT, false)
                    && !moveItemStackTo(stack, 0, STORAGE_END, false)) {
                return ItemStack.EMPTY;
            }
        }
        if (stack.isEmpty()) {
            slot.set(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return original;
    }

    @Override
    public boolean stillValid(Player player) {
        return personContainer.stillValid(player);
    }

    private static int armorIndex(ArmorType type) {
        return Inventory.ARMOR_START + type.ordinal();
    }

    /** An armor slot that only accepts the matching piece and holds one item. */
    private static Slot armorSlot(Container container, int index, int x, int y, EquipmentSlot required) {
        return new Slot(container, index, x, y) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
                return equippable != null && equippable.slot() == required;
            }

            @Override
            public int getMaxStackSize() {
                return 1;
            }
        };
    }
}
