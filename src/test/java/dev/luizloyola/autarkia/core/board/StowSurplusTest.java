package dev.luizloyola.autarkia.core.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.board.WorkItem;
import dev.luizloyola.anima.core.brain.task.PutAwaySurplus;
import dev.luizloyola.anima.core.inv.ItemCall;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.core.inv.ItemStack;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The routine path: a low-priority errand posted when the cargo is worth a trip, which the arbiter
 * can only pick up at a task boundary because work never preempts.
 */
class StowSurplusTest {

    private static final ItemSpec LOGS = ItemSpec.anyOf(Set.of("minecraft:oak_log"));

    /** Fills {@code slots} of the pack with cargo nobody has spoken for. */
    private static void cargo(BoardBrainContext ctx, int slots) {
        for (int slot = 0; slot < slots; slot++) {
            ctx.inventory().set(slot, ItemStack.of("minecraft:oak_log", 64, 64));
        }
    }

    /** Runs {@code n} whole cadence beats. */
    private static void beats(StowSurplus stow, BoardBrainContext ctx, int n) {
        for (int tick = 0; tick < n * StowSurplus.CHECK_INTERVAL; tick++) {
            stow.tick(ctx);
        }
    }

    @Test
    void itPostsOnceWhenTheCargoCrossesTheLine() {
        BoardBrainContext ctx = new BoardBrainContext();
        cargo(ctx, StowSurplus.SURPLUS_SLOTS);
        StowSurplus stow = new StowSurplus(0);

        beats(stow, ctx, 2); // beat one is the warm-up skip
        assertEquals(1, stow.open().size());

        beats(stow, ctx, 3);
        assertEquals(1, stow.open().size(), "still one errand, not one per beat");
    }

    @Test
    void itSaysNothingWhileTheCargoIsBelowTheLine() {
        BoardBrainContext ctx = new BoardBrainContext();
        cargo(ctx, StowSurplus.SURPLUS_SLOTS - 1);
        StowSurplus stow = new StowSurplus(0);

        beats(stow, ctx, 4);

        assertTrue(stow.open().isEmpty(), "a few loose stacks are not worth a walk");
    }

    @Test
    void itWithdrawsWhenThePackDropsBackUnder() {
        BoardBrainContext ctx = new BoardBrainContext();
        cargo(ctx, StowSurplus.SURPLUS_SLOTS);
        StowSurplus stow = new StowSurplus(0);
        beats(stow, ctx, 2);
        assertEquals(1, stow.open().size());

        for (int slot = 0; slot < StowSurplus.SURPLUS_SLOTS; slot++) {
            ctx.inventory().set(slot, ItemStack.EMPTY);
        }
        beats(stow, ctx, 2);

        assertTrue(stow.open().isEmpty(), "a condition, not an outcome");
    }

    @Test
    void itNeverYanksAnErrandSomebodyIsAlreadyWalking() {
        BoardBrainContext ctx = new BoardBrainContext();
        cargo(ctx, StowSurplus.SURPLUS_SLOTS);
        StowSurplus stow = new StowSurplus(0);
        beats(stow, ctx, 2);
        WorkItem errand = stow.open().get(0);
        stow.claimed(errand);

        for (int slot = 0; slot < StowSurplus.SURPLUS_SLOTS; slot++) {
            ctx.inventory().set(slot, ItemStack.EMPTY);
        }
        beats(stow, ctx, 2);

        assertEquals(1, stow.open().size(),
                "pulling work out from under a member mid-walk teaches them to ignore the board");
    }

    @Test
    void aPackOfSpokenForGoodsIsNeverWorthATrip() {
        BoardBrainContext ctx = new BoardBrainContext();
        cargo(ctx, StowSurplus.SURPLUS_SLOTS);
        ctx.reserved = List.of(ItemCall.need(LOGS, 64 * 36));
        StowSurplus stow = new StowSurplus(0);

        beats(stow, ctx, 4);

        assertTrue(stow.open().isEmpty(), "full of things somebody asked them to hold");
    }

    @Test
    void theErrandIsTheSameGoalTheInstinctRuns() {
        BoardBrainContext ctx = new BoardBrainContext();
        cargo(ctx, StowSurplus.SURPLUS_SLOTS);
        StowSurplus stow = new StowSurplus(0);
        beats(stow, ctx, 2);

        assertInstanceOf(PutAwaySurplus.class, stow.open().get(0).root(),
                "one behaviour, two motivations — if these diverge the split is fake");
    }

    @Test
    void itLosesToRealWork() {
        BoardBrainContext ctx = new BoardBrainContext();
        cargo(ctx, StowSurplus.SURPLUS_SLOTS);
        StowSurplus stow = new StowSurplus(0);
        beats(stow, ctx, 2);

        assertTrue(stow.open().get(0).priority() < KeepStocked.CHECK_INTERVAL,
                "sanity: a priority, not a tick count");
        assertTrue(stow.open().get(0).priority() <= 0.15,
                "low enough that anything a project actually wants done wins the board");
        assertFalse(stow.finished(), "and it is never done");
    }
}
