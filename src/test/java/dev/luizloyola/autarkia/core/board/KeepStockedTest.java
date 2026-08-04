package dev.luizloyola.autarkia.core.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    /** n brain ticks, with the world clock moving as it really does — holds age against it. */
    private void ticks(int n) {
        for (int i = 0; i < n; i++) {
            board.tick(ctx);
            ctx.advance(1);
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
        assertTrue(board.holds(item, me, ctx.now()),
                "still theirs — the project must not have dropped it");
    }

    /** A standing condition is never done with: reaching the target closes no project. */
    @Test
    void beingStockedDoesNotCloseAStandingProject() {
        ctx.inventory().add(ItemStack.of("minecraft:oak_log", 16, 64));
        ticks(KeepStocked.CHECK_INTERVAL * 3);
        assertFalse(board.isEmpty(), "the want persists even when currently satisfied");
    }

    // ── what a restart does to a personal board ──────────────────────────────────────────
    // Layer 3 keeps NOTHING across a restart. A board is rebuilt from constants in the body's
    // field initializer, so these tests build a second one exactly as a reload would.

    @Test
    void aRebuiltBoardWantsTheSameThing() {
        ticks(KeepStocked.CHECK_INTERVAL * 2);
        WorkItem before = work.bestAvailable(ctx).orElseThrow();

        PersonalBoard reloaded = new PersonalBoard();
        reloaded.post(new KeepStocked(Stock.LOGS, 16, 0.35, 0));
        WorkSource work2 = reloaded.viewFor(() -> me);
        for (int i = 0; i < KeepStocked.CHECK_INTERVAL * 2; i++) {
            reloaded.tick(ctx);
            ctx.advance(1);
        }
        assertEquals(before.describe(), work2.bestAvailable(ctx).orElseThrow().describe(),
                "the goal is a constant of the body, so a reload asks for exactly the same errand");
    }

    @Test
    void aRebuiltBoardOffersAnErrandNobodyIsHoldingAnyMore() {
        // `claimed` says somebody is out there working this item, and after a restart nobody is —
        // the arbiter's claim is tier 0 and went with the process. Carrying the flag across ON its
        // own would leave a board that never offers the errand again and never withdraws it.
        // Carrying both halves (the hold and the worker's commitment) is what layer 3 will do
        // once work items have durable identity (decision: Luiz); it buys nothing here, where a
        // personal board's holder is always its owner. This test pins the CURRENT shape.
        ticks(KeepStocked.CHECK_INTERVAL * 2);
        WorkItem item = work.bestAvailable(ctx).orElseThrow();
        work.claimed(item, ctx);
        assertTrue(work.bestAvailable(ctx).isEmpty(), "claimed, so not on offer to anyone");

        PersonalBoard reloaded = new PersonalBoard();
        reloaded.post(new KeepStocked(Stock.LOGS, 16, 0.35, 0));
        WorkSource work2 = reloaded.viewFor(() -> me);
        for (int i = 0; i < KeepStocked.CHECK_INTERVAL * 2; i++) {
            reloaded.tick(ctx);
            ctx.advance(1);
        }
        assertTrue(work2.bestAvailable(ctx).isPresent(),
                "a reload starts unclaimed, so the errand is on offer again");
    }

    @Test
    void aRebuiltBoardReadsProgressOffThePackRatherThanRememberingIt() {
        // The other half of why nothing needs saving: a standing project's progress is not a number
        // it keeps, it is what the body is carrying — and the pack is tier 1, already durable.
        ctx.inventory().add(ItemStack.of("minecraft:oak_log", 16, 64));
        PersonalBoard reloaded = new PersonalBoard();
        reloaded.post(new KeepStocked(Stock.LOGS, 16, 0.35, 0));
        WorkSource work2 = reloaded.viewFor(() -> me);
        for (int i = 0; i < KeepStocked.CHECK_INTERVAL * 3; i++) {
            reloaded.tick(ctx);
            ctx.advance(1);
        }
        assertTrue(work2.bestAvailable(ctx).isEmpty(),
                "already stocked, so the rebuilt project posts nothing");
    }
}
