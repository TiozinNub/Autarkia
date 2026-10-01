package dev.luizloyola.autarkia.core.board;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.instinct.UnburdenInstinct;
import dev.luizloyola.anima.core.brain.task.FakeContext;
import dev.luizloyola.anima.core.brain.task.PutAwaySurplus;
import dev.luizloyola.anima.core.inv.Inventory;
import dev.luizloyola.anima.core.inv.ItemStack;
import dev.luizloyola.autarkia.core.person.PersonSpecies;
import org.junit.jupiter.api.Test;

/**
 * A woodcutter's haul home, against a Person's real profile: carried by the load, and
 * always gone before unburden would take the pack to the NEAREST store instead.
 */
class HaulLineTest {

    private static FakeContext person() {
        FakeContext ctx = new FakeContext();
        ctx.profile = PersonSpecies.PROFILE.fixed();
        return ctx;
    }

    @Test
    void oneTreeInAMixedWoodIsNotALoad() {
        FakeContext ctx = person();
        Inventory pack = ctx.percepts.inventory();
        pack.set(0, ItemStack.of("minecraft:oak_log", 5, 64));
        pack.set(1, ItemStack.of("minecraft:birch_log", 4, 64));
        pack.set(2, ItemStack.of("minecraft:oak_sapling", 2, 64));
        pack.set(3, ItemStack.of("minecraft:stick", 3, 64));
        pack.set(4, ItemStack.of("minecraft:leaf_litter", 2, 64));

        assertTrue(new PutAwaySurplus(FellTrees.HAUL_LINE).satisfied(ctx),
                "five kinds of sixteen items sent a settler home after every tree");
    }

    @Test
    void threeStacksOfLogsAre() {
        FakeContext ctx = person();
        for (int slot = 0; slot < 3; slot++) {
            ctx.percepts.inventory().set(slot, ItemStack.of("minecraft:oak_log", 64, 64));
        }
        assertFalse(new PutAwaySurplus(FellTrees.HAUL_LINE).satisfied(ctx));
    }

    @Test
    void wheneverUnburdenWouldBidTheHaulHasAlreadyGone() {
        for (int empty = 0; empty <= Inventory.ARMOR_START; empty++) {
            FakeContext ctx = person();
            for (int slot = 0; slot < Inventory.ARMOR_START - empty; slot++) {
                ctx.percepts.inventory().set(slot, ItemStack.of("minecraft:kind_" + slot, 1, 64));
            }
            if (new UnburdenInstinct().pressure(ctx) > 0.0) {
                assertFalse(new PutAwaySurplus(FellTrees.HAUL_LINE).satisfied(ctx),
                        "a pack of odds and ends with " + empty + " slots free");
            }
        }
    }
}
