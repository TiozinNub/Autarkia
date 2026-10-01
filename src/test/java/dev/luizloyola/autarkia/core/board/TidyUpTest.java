package dev.luizloyola.autarkia.core.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.board.WorkItem;
import dev.luizloyola.anima.core.brain.board.WorkSource;
import dev.luizloyola.anima.core.brain.task.PrimitiveTask;
import dev.luizloyola.anima.core.brain.task.TaskStatus;
import dev.luizloyola.anima.core.inv.Inventory;
import dev.luizloyola.anima.core.inv.ItemStack;
import dev.luizloyola.autarkia.core.person.SettlerPack;
import org.junit.jupiter.api.Test;

/** A settler sorts a pack that has drifted, and leaves a tidy one alone. */
class TidyUpTest {

    private final BoardBrainContext ctx = new BoardBrainContext();
    private final AgentId me = AgentId.random();
    private final PersonalBoard board = new PersonalBoard();
    private final WorkSource work = board.viewFor(() -> me);

    TidyUpTest() {
        board.post(new TidyUp());
        ctx.inventory().setLayout(SettlerPack.INSTANCE);
    }

    private void ticks(int n) {
        for (int i = 0; i < n; i++) {
            board.tick(ctx);
            ctx.advance(1);
        }
    }

    /** Logs on the hotbar, a sword in the backpack: what a draw for a bridge leaves behind. */
    private void drift() {
        Inventory pack = ctx.inventory();
        pack.set(0, ItemStack.of("minecraft:oak_log", 64, 64));
        pack.set(20, ItemStack.of("minecraft:stone_sword", 1, 1));
    }

    @Test
    void aTidyPackIsLeftAlone() {
        ctx.inventory().add(ItemStack.of("minecraft:stone_sword", 1, 1));
        ctx.inventory().add(ItemStack.of("minecraft:oak_log", 64, 64));
        ticks(TidyUp.CHECK_INTERVAL * 3);
        assertTrue(work.bestAvailable(ctx).isEmpty());
    }

    @Test
    void aDriftedPackIsSortedAStackMoveAtATime() {
        drift();
        ticks(TidyUp.CHECK_INTERVAL * 2);
        WorkItem item = work.bestAvailable(ctx).orElseThrow();
        assertEquals(TidyUp.PRIORITY, item.priority());

        PrimitiveTask tidy = (PrimitiveTask) item.root();
        int ticks = 0;
        while (tidy.tick(ctx) == TaskStatus.RUNNING) {
            ctx.advance(1);
            ticks++;
        }
        assertEquals("minecraft:stone_sword", ctx.inventory().get(0).id());
        assertEquals("minecraft:oak_log", ctx.inventory().get(20).id());
        assertTrue(ticks >= 6, "one stack move, paid in handling time");
    }

    @Test
    void anUnclaimedTidyIsWithdrawnOnceThePackIsSorted() {
        drift();
        ticks(TidyUp.CHECK_INTERVAL * 2);
        assertTrue(work.bestAvailable(ctx).isPresent());
        Inventory pack = ctx.inventory();
        pack.set(0, ItemStack.of("minecraft:stone_sword", 1, 1));
        pack.set(20, ItemStack.of("minecraft:oak_log", 64, 64));
        ticks(TidyUp.CHECK_INTERVAL);
        assertTrue(work.bestAvailable(ctx).isEmpty());
    }
}
