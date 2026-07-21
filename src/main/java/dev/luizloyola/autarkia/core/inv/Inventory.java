package dev.luizloyola.autarkia.core.inv;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * A player-shaped carried inventory, as pure simulation state — no {@code net.minecraft}. The
 * <em>source of truth</em>: the {@code mod} layer mirrors the equipment slots onto the entity (for
 * rendering and vanilla mechanics) and persists this model, but the brain reads and writes
 * <em>here</em>.
 *
 * <p>Vanilla's 41 slots, contiguously indexed:
 * <pre>
 *   [ 0 ..  8]  HOTBAR   (9)   — the selectable row; {@link #mainHand()} is the selected one
 *   [ 9 .. 35]  MAIN     (27)  — backpack storage
 *   [36 .. 39]  ARMOR    (4)   — HEAD, CHEST, LEGS, FEET (in {@link ArmorType} order)
 *   [40]        OFFHAND  (1)
 * </pre>
 * {@link #add(ItemStack)} only ever fills the hotbar and main storage — never armor or offhand.
 */
public final class Inventory {
    public static final int HOTBAR_START = 0;
    public static final int HOTBAR_SIZE = 9;
    public static final int MAIN_START = HOTBAR_START + HOTBAR_SIZE; // 9
    public static final int MAIN_SIZE = 27;
    public static final int ARMOR_START = MAIN_START + MAIN_SIZE;    // 36
    public static final int ARMOR_SIZE = 4;
    public static final int OFFHAND_SLOT = ARMOR_START + ARMOR_SIZE; // 40
    public static final int SIZE = OFFHAND_SLOT + 1;                 // 41

    /** The end (exclusive) of the addressable storage region: hotbar + main, no equipment. */
    private static final int STORAGE_END = ARMOR_START; // 36

    /** One slot and what it holds; the non-empty view used for persistence and listing. */
    public record Entry(int slot, ItemStack stack) {
        public Entry {
            Objects.requireNonNull(stack, "stack");
        }
    }

    private final ItemStack[] slots = new ItemStack[SIZE];
    private int selectedSlot;

    public Inventory() {
        Arrays.fill(slots, ItemStack.EMPTY);
    }

    // --- raw slot access -------------------------------------------------------------------------

    public ItemStack get(int slot) {
        return slots[checkSlot(slot)];
    }

    /** Places {@code stack} in {@code slot} (null or empty collapses to {@link ItemStack#EMPTY}). */
    public void set(int slot, ItemStack stack) {
        slots[checkSlot(slot)] = (stack == null || stack.isEmpty()) ? ItemStack.EMPTY : stack;
    }

    // --- selection / equipment views -------------------------------------------------------------

    public int selectedSlot() {
        return selectedSlot;
    }

    /** Selects a hotbar slot (0..8) as the "main hand". */
    public void setSelectedSlot(int slot) {
        if (slot < 0 || slot >= HOTBAR_SIZE) {
            throw new IndexOutOfBoundsException("hotbar slot out of range [0," + HOTBAR_SIZE + "): " + slot);
        }
        this.selectedSlot = slot;
    }

    /** The held item: whatever is in the selected hotbar slot. */
    public ItemStack mainHand() {
        return slots[HOTBAR_START + selectedSlot];
    }

    public ItemStack offhand() {
        return slots[OFFHAND_SLOT];
    }

    public void setOffhand(ItemStack stack) {
        set(OFFHAND_SLOT, stack);
    }

    public ItemStack armor(ArmorType type) {
        return slots[ARMOR_START + type.ordinal()];
    }

    public void setArmor(ArmorType type, ItemStack stack) {
        set(ARMOR_START + type.ordinal(), stack);
    }

    // --- resource operations ---------------------------------------------------------------------

    /**
     * Adds {@code stack} to storage (hotbar then main): topping up same-kind stacks with headroom
     * first, then filling empty slots at the item's stack cap. Armor and offhand are never
     * touched. Returns whatever did not fit, or {@link ItemStack#EMPTY} if it all did.
     */
    public ItemStack add(ItemStack stack) {
        if (stack.isEmpty()) return ItemStack.EMPTY;
        int remaining = stack.count();
        for (int slot = 0; slot < STORAGE_END && remaining > 0; slot++) {
            ItemStack existing = slots[slot];
            if (existing.canStackWith(stack)) {
                int moved = Math.min(remaining, existing.remainingSpace());
                if (moved > 0) {
                    slots[slot] = existing.withCount(existing.count() + moved);
                    remaining -= moved;
                }
            }
        }
        for (int slot = 0; slot < STORAGE_END && remaining > 0; slot++) {
            if (slots[slot].isEmpty()) {
                int moved = Math.min(remaining, stack.maxStackSize());
                slots[slot] = stack.withCount(moved);
                remaining -= moved;
            }
        }
        return stack.withCount(remaining);
    }

    /** Total count of item {@code id} across <em>every</em> slot, equipment included. */
    public int count(String id) {
        int total = 0;
        for (ItemStack stack : slots) {
            if (!stack.isEmpty() && stack.id().equals(id)) total += stack.count();
        }
        return total;
    }

    public boolean contains(String id, int atLeast) {
        return count(id) >= atLeast;
    }

    /**
     * Removes and returns a single unit of the first stored (hotbar/main) stack of item {@code id},
     * <em>preserving its components</em>, or {@link ItemStack#EMPTY} if none is in storage — how an
     * enchanted helmet reaches an equipment slot intact.
     */
    public ItemStack takeOne(String id) {
        for (int slot = 0; slot < STORAGE_END; slot++) {
            ItemStack stack = slots[slot];
            if (!stack.isEmpty() && stack.id().equals(id)) {
                slots[slot] = stack.withCount(stack.count() - 1);
                return stack.withCount(1); 
            }
        }
        return ItemStack.EMPTY;
    }

    /**
     * Removes up to {@code amount} of item {@code id}, scanning all slots in index order. Returns
     * how many were actually removed.
     */
    public int remove(String id, int amount) {
        int toRemove = amount;
        for (int slot = 0; slot < SIZE && toRemove > 0; slot++) {
            ItemStack stack = slots[slot];
            if (!stack.isEmpty() && stack.id().equals(id)) {
                int taken = Math.min(toRemove, stack.count());
                slots[slot] = stack.withCount(stack.count() - taken);
                toRemove -= taken;
            }
        }
        return amount - toRemove;
    }

    // --- bulk / lifecycle ------------------------------------------------------------------------

    public boolean isEmpty() {
        for (ItemStack stack : slots) {
            if (!stack.isEmpty()) return false;
        }
        return true;
    }

    /** Every non-empty slot as an {@link Entry}, in index order — the view persistence iterates. */
    public List<Entry> occupied() {
        List<Entry> out = new ArrayList<>();
        for (int slot = 0; slot < SIZE; slot++) {
            if (!slots[slot].isEmpty()) out.add(new Entry(slot, slots[slot]));
        }
        return out;
    }

    /** Replaces this inventory's contents (and selection) with {@code other}'s — used on load. */
    public void copyFrom(Inventory other) {
        System.arraycopy(other.slots, 0, this.slots, 0, SIZE);
        this.selectedSlot = other.selectedSlot;
    }

    public void clear() {
        Arrays.fill(slots, ItemStack.EMPTY);
    }

    private static int checkSlot(int slot) {
        if (slot < 0 || slot >= SIZE) {
            throw new IndexOutOfBoundsException("slot out of range [0," + SIZE + "): " + slot);
        }
        return slot;
    }
}
