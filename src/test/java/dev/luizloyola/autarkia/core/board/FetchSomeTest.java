package dev.luizloyola.autarkia.core.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.task.FakeContext;
import dev.luizloyola.anima.core.brain.task.Method;
import dev.luizloyola.anima.core.brain.task.Producers;
import dev.luizloyola.anima.core.brain.task.Task;
import dev.luizloyola.anima.core.brain.task.TaskExecutor;
import dev.luizloyola.anima.core.brain.task.TaskStatus;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.core.inv.ItemStack;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class FetchSomeTest {

    private static final ItemSpec FAR_THING = ItemSpec.register(
            new ItemSpec("fetch-some-test-far", id -> id.equals("test:far_thing")));

    static {
        Producers.register(FAR_THING, FAR_THING::matches, wanted -> new Method() {
            @Override
            public boolean applicable(BrainContext ctx) {
                return true;
            }

            @Override
            public double estimateCost(BrainContext ctx) {
                return 50;
            }

            @Override
            public List<Task> decompose(BrainContext ctx) {
                return List.of();
            }

            @Override
            public String describe() {
                return "walk to the far one";
            }
        });
    }

    @Test
    void aTripPricedOutOfEverySourceFailsPricedOut() {
        FakeContext ctx = new FakeContext();
        ctx.costTolerance = 10;
        TaskExecutor executor = new TaskExecutor();

        executor.run(new GatheringErrand(FAR_THING, 16), ctx);
        for (int i = 0; i < 20 && executor.isBusy(); i++) {
            executor.tick(ctx);
        }

        assertEquals(Optional.of(TaskStatus.FAILED), executor.lastStatus());
        assertTrue(executor.failedOnPrice(),
                "the board grows a gather's budget only on a priced-out failure, and Emily's "
                        + "log gather read 'bring the logs home: no applicable way' nine times in ten: "
                        + executor.failureReason().orElse(""));
    }

    @Test
    void whatIsHeldIsTheFallbackNeverTheFirstChoice() {
        FetchSome fetch = new FetchSome(Stock.LOGS, 64);
        FakeContext ctx = new FakeContext();
        Method settle = fetch.methods().get(1);

        assertFalse(settle.applicable(ctx), "nothing held: the fetch's own failure stands");
        ctx.percepts.inventory.set(0, ItemStack.of("minecraft:oak_log", 9, 64));
        assertTrue(settle.applicable(ctx), "nine held: they go home");
        assertEquals(fetch.methods().get(0).estimateCost(ctx), settle.estimateCost(ctx),
                "a tie, which goes to the fetch, so nine held still go for the other 55");
    }
}
