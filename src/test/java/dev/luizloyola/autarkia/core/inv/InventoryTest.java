package dev.luizloyola.autarkia.core.inv;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Headless tests for the pure 41-slot player-shaped {@link Inventory} and its {@link ItemStack}
 * value type. No Minecraft — the whole point of the {@code core} layer.
 */
class InventoryTest {

    private static ItemStack logs(int count) {
        return ItemStack.of("minecraft:oak_log", count, 64);
    }

    @Test
    void addIntoEmptyInventoryTakesTheFirstSlotAndFits() {
        Inventory inv = new Inventory();
        assertTrue(inv.add(logs(12)).isEmpty(), "everything fit -> no remainder");
        assertEquals(12, inv.count("minecraft:oak_log"));
        assertEquals(logs(12), inv.get(0), "fills the first hotbar slot first");
    }

    @Test
    void addTopsUpExistingStackBeforeUsingNewSlots() {
        Inventory inv = new Inventory();
        inv.add(logs(60));
        assertTrue(inv.add(logs(3)).isEmpty());
        assertEquals(logs(63), inv.get(0));
        assertTrue(inv.get(1).isEmpty());
    }

    @Test
    void addSpillsOverTheStackCapIntoTheNextSlot() {
        Inventory inv = new Inventory();
        assertTrue(inv.add(logs(100)).isEmpty());
        assertEquals(logs(64), inv.get(0));
        assertEquals(logs(36), inv.get(1));
        assertEquals(100, inv.count("minecraft:oak_log"));
    }

    @Test
    void addReturnsTheRemainderWhenStorageIsFull() {
        Inventory inv = new Inventory();
        // 36 storage slots (9 hotbar + 27 main) x 64 = 2304 capacity for one kind.
        assertTrue(inv.add(logs(2304)).isEmpty());
        ItemStack overflow = inv.add(logs(10));
        assertEquals(logs(10), overflow, "10 could not fit -> returned as the remainder");
        assertEquals(2304, inv.count("minecraft:oak_log"));
    }

    @Test
    void addNeverTouchesArmorOrOffhand() {
        Inventory inv = new Inventory();
        inv.add(logs(2304)); // fills all 36 storage slots
        assertTrue(inv.armor(ArmorType.HEAD).isEmpty());
        assertTrue(inv.offhand().isEmpty());
        // With storage full, more overflows rather than bleeding into equipment.
        assertFalse(inv.add(logs(1)).isEmpty());
    }

    @Test
    void differentKindsDoNotMerge() {
        Inventory inv = new Inventory();
        inv.add(logs(1));
        inv.add(ItemStack.of("minecraft:cobblestone", 1, 64));
        assertEquals(1, inv.count("minecraft:oak_log"));
        assertEquals(1, inv.count("minecraft:cobblestone"));
        assertEquals(logs(1), inv.get(0));
        assertEquals(ItemStack.of("minecraft:cobblestone", 1, 64), inv.get(1));
    }

    @Test
    void removeSpansSlotsAndReportsWhatWasTaken() {
        Inventory inv = new Inventory();
        inv.add(logs(100)); // slot0=64, slot1=36
        assertEquals(70, inv.remove("minecraft:oak_log", 70));
        assertEquals(30, inv.count("minecraft:oak_log"));
        assertEquals(30, inv.remove("minecraft:oak_log", 50));
        assertTrue(inv.isEmpty());
    }

    @Test
    void containsChecksTheThreshold() {
        Inventory inv = new Inventory();
        inv.add(logs(5));
        assertTrue(inv.contains("minecraft:oak_log", 5));
        assertFalse(inv.contains("minecraft:oak_log", 6));
        assertFalse(inv.contains("minecraft:dirt", 1));
    }

    @Test
    void selectedHotbarSlotIsTheMainHand() {
        Inventory inv = new Inventory();
        inv.set(0, logs(1));
        ItemStack sword = ItemStack.of("minecraft:iron_sword", 1, 1);
        inv.set(3, sword);
        inv.setSelectedSlot(3);
        assertEquals(sword, inv.mainHand());
        assertEquals(3, inv.selectedSlot());
    }

    @Test
    void armorIsAddressedByType() {
        Inventory inv = new Inventory();
        ItemStack helmet = ItemStack.of("minecraft:diamond_helmet", 1, 1);
        inv.setArmor(ArmorType.HEAD, helmet);
        assertEquals(helmet, inv.armor(ArmorType.HEAD));
        assertEquals(helmet, inv.get(Inventory.ARMOR_START), "HEAD is the first armor slot");
        assertTrue(inv.armor(ArmorType.FEET).isEmpty());
    }

    @Test
    void occupiedListsOnlyNonEmptySlotsInIndexOrder() {
        Inventory inv = new Inventory();
        inv.set(2, logs(4));
        inv.setArmor(ArmorType.CHEST, ItemStack.of("minecraft:leather_chestplate", 1, 1));
        inv.setOffhand(ItemStack.of("minecraft:shield", 1, 1));
        List<Inventory.Entry> occupied = inv.occupied();
        assertEquals(3, occupied.size());
        assertEquals(2, occupied.get(0).slot());
        assertEquals(Inventory.ARMOR_START + ArmorType.CHEST.ordinal(), occupied.get(1).slot());
        assertEquals(Inventory.OFFHAND_SLOT, occupied.get(2).slot());
    }

    @Test
    void copyFromReplacesContentsAndSelection() {
        Inventory source = new Inventory();
        source.add(logs(20));
        source.setSelectedSlot(5);
        source.setArmor(ArmorType.FEET, ItemStack.of("minecraft:iron_boots", 1, 1));

        Inventory target = new Inventory();
        target.add(ItemStack.of("minecraft:dirt", 3, 64)); // pre-existing junk, must be gone after
        target.copyFrom(source);

        assertEquals(20, target.count("minecraft:oak_log"));
        assertEquals(0, target.count("minecraft:dirt"));
        assertEquals(5, target.selectedSlot());
        assertEquals(ItemStack.of("minecraft:iron_boots", 1, 1), target.armor(ArmorType.FEET));
    }

    @Test
    void withCountZeroCollapsesToEmpty() {
        assertTrue(logs(5).withCount(0).isEmpty());
        assertEquals(ItemStack.EMPTY, logs(5).withCount(-3));
        assertEquals(3, logs(5).withCount(3).count());
    }

    @Test
    void sameKindDifferentComponentsDoNotStack() {
        Inventory inv = new Inventory();
        // opaque component tokens stand in for compat's SNBT — core only compares them
        inv.add(ItemStack.of("minecraft:diamond_pickaxe", 1, 1, "sharpness"));
        inv.add(ItemStack.of("minecraft:diamond_pickaxe", 1, 1, "efficiency"));
        assertEquals(2, inv.count("minecraft:diamond_pickaxe"));
        assertEquals("sharpness", inv.get(0).components());
        assertEquals("efficiency", inv.get(1).components(), "different components -> separate slot");
        assertTrue(inv.get(2).isEmpty());
    }

    @Test
    void sameComponentsStackTogether() {
        Inventory inv = new Inventory();
        inv.add(ItemStack.of("minecraft:writable_book", 8, 64, "chapter1"));
        assertTrue(inv.add(ItemStack.of("minecraft:writable_book", 8, 64, "chapter1")).isEmpty());
        assertEquals(16, inv.get(0).count(), "identical components merge");
        assertTrue(inv.get(1).isEmpty());
    }

    @Test
    void takeOnePreservesComponents() {
        Inventory inv = new Inventory();
        inv.add(ItemStack.of("minecraft:diamond_helmet", 1, 1, "protection"));
        ItemStack taken = inv.takeOne("minecraft:diamond_helmet");
        assertEquals(1, taken.count());
        assertEquals("protection", taken.components(), "the specific stored item, components intact");
        assertTrue(inv.isEmpty());
        assertTrue(inv.takeOne("minecraft:diamond_helmet").isEmpty(), "nothing left to take");
    }

    @Test
    void withCountPreservesComponents() {
        ItemStack enchanted = ItemStack.of("minecraft:diamond_sword", 1, 1, "looting");
        assertEquals("looting", enchanted.withCount(5).components());
    }
}
