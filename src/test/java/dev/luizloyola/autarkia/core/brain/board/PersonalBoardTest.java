package dev.luizloyola.autarkia.core.brain.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.autarkia.core.inv.Inventory;
import dev.luizloyola.autarkia.core.inv.ItemStack;
import org.junit.jupiter.api.Test;

/** The standing stock project's lifecycle: cadence, predicate, claim, completion, cooldown. */
class PersonalBoardTest {

    private final PersonalBoard board = new PersonalBoard();
    private final Inventory inventory = new Inventory();

    private void stock(int logs) {
        inventory.add(ItemStack.of("minecraft:oak_log", logs, 64));
    }

    /** First tick is the warm-up grace (look before wanting); the board acts from the second on. */
    private void prime() {
        board.tick(0, inventory);
    }

    @Test
    void theFirstTickIsAGraceNotAWant() {
        board.tick(0, inventory); // understocked, but she has not looked at the world yet
        assertTrue(board.bestAvailable().isEmpty(), "no cold-claim race on tick one");
    }

    @Test
    void postsWhenUnderstockedAndOnlyOnTheCadence() {
        prime();
        board.tick(PersonalBoard.CHECK_INTERVAL_TICKS, inventory);
        WorkItem item = board.bestAvailable().orElseThrow();
        assertEquals(PersonalBoard.STOCK_TARGET, item.count());
        assertEquals(PersonalBoard.STOCK_PRIORITY, item.priority());

        board.tick(PersonalBoard.CHECK_INTERVAL_TICKS + 50, inventory); // between checks: no churn
        assertEquals(item, board.bestAvailable().orElseThrow());
    }

    @Test
    void staysQuietWhenStocked() {
        stock(PersonalBoard.STOCK_TARGET);
        prime();
        board.tick(PersonalBoard.CHECK_INTERVAL_TICKS, inventory);
        assertTrue(board.bestAvailable().isEmpty());
    }

    @Test
    void anUnclaimedItemRetiresWhenTheWantClosesByOtherMeans() {
        prime();
        board.tick(PersonalBoard.CHECK_INTERVAL_TICKS, inventory);
        assertTrue(board.bestAvailable().isPresent());
        stock(PersonalBoard.STOCK_TARGET); // a gift, a pickup — the pack filled itself
        board.tick(PersonalBoard.CHECK_INTERVAL_TICKS * 2, inventory);
        assertTrue(board.bestAvailable().isEmpty());
    }

    @Test
    void claimHidesTheItemAndCompletionReopensTheWantLater() {
        prime();
        board.tick(PersonalBoard.CHECK_INTERVAL_TICKS, inventory);
        WorkItem first = board.bestAvailable().orElseThrow();
        board.claim(first);
        assertTrue(board.bestAvailable().isEmpty(), "claimed = off the offer");

        board.complete(first);
        board.tick(PersonalBoard.CHECK_INTERVAL_TICKS * 2, inventory); // pack still empty: want reopens
        WorkItem second = board.bestAvailable().orElseThrow();
        assertNotEquals(first.id(), second.id(), "a fresh posting, not the ghost of the old one");
    }

    @Test
    void failureCoolsTheWantDown() {
        prime();
        board.tick(PersonalBoard.CHECK_INTERVAL_TICKS, inventory);
        WorkItem item = board.bestAvailable().orElseThrow();
        board.claim(item);
        board.fail(item);

        board.tick(PersonalBoard.CHECK_INTERVAL_TICKS * 2, inventory);
        assertTrue(board.bestAvailable().isEmpty(), "cooling down — the retry is paced");

        board.tick(PersonalBoard.CHECK_INTERVAL_TICKS + PersonalBoard.FAIL_COOLDOWN_TICKS
                + PersonalBoard.CHECK_INTERVAL_TICKS, inventory);
        assertFalse(board.bestAvailable().isEmpty(), "cooldown over — wanted again");
    }
}
