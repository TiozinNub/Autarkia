package dev.luizloyola.autarkia.core.board;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.board.WorkItem;
import dev.luizloyola.anima.core.brain.board.WorkSource;
import dev.luizloyola.anima.core.inv.ItemStack;
import org.junit.jupiter.api.Test;

/**
 * The standing stock want, now a project on a personal board: cadence posting, withdrawal,
 * completion, retry pacing — the four behaviours {@code StockBoard} was tested for, now asserted
 * through the board and the member view, the path the brain takes.
 */
class KeepStockedTest {

    private final BoardBrainContext ctx = new BoardBrainContext();
    private final AgentId me = AgentId.random();
    private final PersonalBoard board = new PersonalBoard();
    private final WorkSource work = board.viewFor(() -> me);

    KeepStockedTest() {
        board.post(new KeepStocked(Stock.LOGS, 16, 0.35, 0));
    }

    private void ticks(int n) {
        for (int i = 0; i < n; i++) {
            board.tick(ctx);
        }
    }

    @Test
    void postsOnItsSecondBeatWhenShort() {
        ticks(KeepStocked.CHECK_INTERVAL);
        assertTrue(work.bestAvailable(ctx).isEmpty(),
                "beat one is the warm-up: they look before they want");
        ticks(KeepStocked.CHECK_INTERVAL);
        assertTrue(work.bestAvailable(ctx).isPresent(), "short on logs -> posted");
    }

    @Test
    void withdrawsAnUnclaimedItemThatBecameMoot() {
        ticks(KeepStocked.CHECK_INTERVAL * 2);
        assertTrue(work.bestAvailable(ctx).isPresent());
        ctx.inventory().add(ItemStack.of("minecraft:oak_log", 16, 64));
        ticks(KeepStocked.CHECK_INTERVAL);
        assertTrue(work.bestAvailable(ctx).isEmpty(), "stocked by luck -> withdrawn");
    }

    @Test
    void claimHidesTheItemAndCompletionClosesIt() {
        ticks(KeepStocked.CHECK_INTERVAL * 2);
        WorkItem item = work.bestAvailable(ctx).orElseThrow();
        work.claimed(item, ctx);
        assertTrue(work.bestAvailable(ctx).isEmpty(), "claimed items are not on offer");
        ctx.inventory().add(ItemStack.of("minecraft:oak_log", 16, 64));
        work.completed(item, ctx);
        ticks(KeepStocked.CHECK_INTERVAL);
        assertTrue(work.bestAvailable(ctx).isEmpty(), "stocked -> nothing re-posted");
    }

    @Test
    void failurePacesTheRetry() {
        ticks(KeepStocked.CHECK_INTERVAL * 2);
        WorkItem item = work.bestAvailable(ctx).orElseThrow();
        work.claimed(item, ctx);
        work.failed(item, ctx);
        ticks(KeepStocked.FAIL_COOLDOWN - KeepStocked.CHECK_INTERVAL);
        assertFalse(work.bestAvailable(ctx).isPresent(), "cooling: the want waits");
        ticks(KeepStocked.FAIL_COOLDOWN);
        assertTrue(work.bestAvailable(ctx).isPresent(), "cooldown over -> posted again");
    }

    /**
     * The guard the old board had and the project must keep: an errand somebody is already
     * walking to is not withdrawn under them just because a lucky pickup filled the pack. It
     * satisfies itself and reports, and that is how the claim clears.
     */
    @Test
    void aClaimedErrandIsNeverWithdrawnUnderTheWorker() {
        ticks(KeepStocked.CHECK_INTERVAL * 2);
        WorkItem item = work.bestAvailable(ctx).orElseThrow();
        work.claimed(item, ctx);
        ctx.inventory().add(ItemStack.of("minecraft:oak_log", 16, 64));
        ticks(KeepStocked.CHECK_INTERVAL * 2);
        assertTrue(board.holds(item, me), "still theirs — the project must not have dropped it");
    }

    /** A standing condition is never done with: reaching the target closes no project. */
    @Test
    void beingStockedDoesNotCloseAStandingProject() {
        ctx.inventory().add(ItemStack.of("minecraft:oak_log", 16, 64));
        ticks(KeepStocked.CHECK_INTERVAL * 3);
        assertFalse(board.isEmpty(), "the want persists even when currently satisfied");
    }
}
