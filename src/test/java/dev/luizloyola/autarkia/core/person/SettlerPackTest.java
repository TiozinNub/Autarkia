package dev.luizloyola.autarkia.core.person;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.inv.Inventory;
import dev.luizloyola.anima.core.inv.ItemStack;
import dev.luizloyola.anima.core.inv.Wear;
import java.util.OptionalDouble;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Where a settler's pickups land. */
class SettlerPackTest {

    private final Inventory pack = new Inventory();

    SettlerPackTest() {
        pack.setLayout(SettlerPack.INSTANCE);
        Wear.install(stack -> stack.id().endsWith("_sword") || stack.id().endsWith("_pickaxe")
                || stack.id().endsWith("_axe") || stack.id().endsWith("_shovel")
                || stack.id().equals("minecraft:shears")
                ? OptionalDouble.of(1.0) : OptionalDouble.empty());
    }

    @AfterEach
    void uninstall() {
        Wear.install(null);
    }

    private static ItemStack one(String id) {
        return ItemStack.of("minecraft:" + id, 1, 1);
    }

    @Test
    void theFourToolsTakeTheirOwnSlots() {
        pack.add(one("stone_shovel"));
        pack.add(one("wooden_axe"));
        pack.add(one("stone_pickaxe"));
        pack.add(one("iron_sword"));
        assertEquals("minecraft:iron_sword", pack.get(0).id());
        assertEquals("minecraft:stone_pickaxe", pack.get(1).id());
        assertEquals("minecraft:wooden_axe", pack.get(2).id());
        assertEquals("minecraft:stone_shovel", pack.get(3).id());
    }

    @Test
    void anOldToolSitsBesideItsSuccessor() {
        pack.add(one("stone_axe"));
        pack.add(one("wooden_axe"));
        assertEquals("minecraft:stone_axe", pack.get(2).id());
        assertTrue(pack.get(1).id().equals("minecraft:wooden_axe")
                || pack.get(3).id().equals("minecraft:wooden_axe"), "one slot over, still on the hotbar");
    }

    @Test
    void logsGoToTheBackpack() {
        pack.add(ItemStack.of("minecraft:oak_log", 64, 64));
        assertEquals("minecraft:oak_log", pack.get(Inventory.MAIN_START).id());
        assertEquals(Inventory.HOTBAR_SIZE, pack.emptyHotbar());
    }

    @Test
    void anyOtherToolIsBetterOnTheHotbarThanInTheBackpack() {
        pack.add(one("shears"));
        assertTrue(pack.get(Inventory.MAIN_START).isEmpty());
        assertEquals(Inventory.HOTBAR_SIZE - 1, pack.emptyHotbar());
    }
}
