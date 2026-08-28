package dev.luizloyola.autarkia.core.board;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.luizloyola.anima.core.inv.Inventory;
import dev.luizloyola.anima.core.inv.ItemStack;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * {@link CarrySplit}'s formula: a trip is what this body can still carry of what the project wants,
 * capped at one stack and never more than is actually left.
 */
class SplitTest {

    private static BoardBrainContext packHolding(ItemStack... stacks) {
        BoardBrainContext ctx = new BoardBrainContext();
        for (int slot = 0; slot < stacks.length; slot++) {
            ctx.inventory().set(slot, stacks[slot]);
        }
        return ctx;
    }

    private static BoardBrainContext fullPack() {
        ItemStack[] stacks = new ItemStack[Inventory.ARMOR_START];
        // Cobble, not logs: a full pack of the WANTED thing would have headroom of its own.
        Arrays.fill(stacks, ItemStack.of("minecraft:cobblestone", 64, 64));
        return packHolding(stacks);
    }

    private static ItemStack logs(int count) {
        return ItemStack.of("minecraft:spruce_log", count, 64);
    }

    @Test
    void anEmptySlotIsWorthAFullTrip() {
        assertEquals(64, CarrySplit.INSTANCE.tripFor(512, Stock.LOGS, packHolding()));
    }

    @Test
    void aClaimNeverExceedsWhatIsLeft() {
        assertEquals(9, CarrySplit.INSTANCE.tripFor(9, Stock.LOGS, packHolding()),
                "nine outstanding is a trip of nine, not a trip of MIN_TRIP");
    }

    @Test
    void aFullPackTakesNothing() {
        assertEquals(0, CarrySplit.INSTANCE.tripFor(512, Stock.LOGS, fullPack()),
                "a body with no room must be declined rather than sent");
    }

    @Test
    void headroomInAMatchingStackCounts() {
        // Every storage slot full, one of them holding 40 of 64 logs: 24 of headroom and no more.
        ItemStack[] stacks = new ItemStack[Inventory.ARMOR_START];
        Arrays.fill(stacks, ItemStack.of("minecraft:cobblestone", 64, 64));
        stacks[0] = logs(40);
        assertEquals(24, CarrySplit.INSTANCE.tripFor(512, Stock.LOGS, packHolding(stacks)));
    }

    @Test
    void nothingIsOutstandingSoNothingIsTaken() {
        assertEquals(0, CarrySplit.INSTANCE.tripFor(0, Stock.LOGS, packHolding()));
    }
}
