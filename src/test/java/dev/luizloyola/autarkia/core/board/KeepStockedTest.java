package dev.luizloyola.autarkia.core.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.board.WorkItem;
import dev.luizloyola.anima.core.brain.board.WorkSource;
import dev.luizloyola.anima.core.inv.ItemStack;
import java.util.List;
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

    // ── a board built fresh, with nothing to restore ─────────────────────────────────────
    // A board with no saved state: a world written before any of this, or a project posted since.
    // These assert the FALLBACK, not the design — the restore path is the section below.

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
        // With nothing saved, an unclaimed board is the only sound start: a claim flag with no
        // claim behind it leaves a want spoken for by nobody. Both halves are carried now — see
        // aRestoredClaimIsHeldRatherThanOfferedAround — needing no durable work-item identity, since
        // a personal board has one member and one slot. That identity is a SHARED board's problem.
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
        // PROGRESS is not a number a standing project keeps: it is what the body carries, and the
        // pack was always durable. The cadence and the claim, though, cannot be re-derived.
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

    // ── continuity ───────────────────────────────────────────────────────────────────────────
    // Under the rule that replaced step 4's "a board saves nothing" — anything outliving its tick
    // survives — the cadence, the open item and the claim are all state. These pin the round trip.

    @Test
    void aRestoredProjectKeepsItsRhythmAndItsOpenErrand() {
        ticks(KeepStocked.CHECK_INTERVAL * 2);
        assertTrue(work.bestAvailable(ctx).isPresent(), "an errand is out");

        KeepStocked before = new KeepStocked(Stock.LOGS, 16, 0.35, 0);
        PersonalBoard reloaded = new PersonalBoard();
        reloaded.post(before);
        before.restore(new KeepStocked.State(0, 137, 4, true, false));

        assertEquals(137, before.snapshot().clock(), "the cadence clock carries");
        assertEquals(4, before.snapshot().beats(), "so does the warm-up count");
        assertTrue(reloaded.viewFor(() -> me).bestAvailable(ctx).isPresent(),
                "an errand that was out is out again, without waiting for a fresh beat");
    }

    @Test
    void aRestoredClaimIsHeldRatherThanOfferedAround() {
        // A claimed errand must come back CLAIMED: offered again, it could be scored and handed to
        // somebody else while its holder is still walking to it.
        KeepStocked project = new KeepStocked(Stock.LOGS, 16, 0.35, 0);
        PersonalBoard reloaded = new PersonalBoard();
        reloaded.post(project);
        reloaded.restore(List.of(new KeepStocked.State(0, 0, 4, true, true)), me, ctx.now());

        assertTrue(reloaded.viewFor(() -> me).bestAvailable(ctx).isEmpty(),
                "held, so not on offer to anyone — including its own owner");
        assertNotNull(project.openItem(), "and the errand itself is back");
    }

    @Test
    void aRetryCooldownIsNotForgivenByAReload() {
        KeepStocked project = new KeepStocked(Stock.LOGS, 16, 0.35, 0);
        PersonalBoard reloaded = new PersonalBoard();
        reloaded.post(project);
        reloaded.restore(List.of(new KeepStocked.State(KeepStocked.FAIL_COOLDOWN, 0, 4, false, false)),
                me, ctx.now());
        for (int i = 0; i < KeepStocked.CHECK_INTERVAL * 2; i++) {
            reloaded.tick(ctx);
            ctx.advance(1);
        }
        assertTrue(reloaded.viewFor(() -> me).bestAvailable(ctx).isEmpty(),
                "still sitting out the retry it was told to sit out");
    }
}
