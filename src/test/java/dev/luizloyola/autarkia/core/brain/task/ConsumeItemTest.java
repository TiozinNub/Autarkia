package dev.luizloyola.autarkia.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.luizloyola.autarkia.core.brain.act.ConsumeState;
import org.junit.jupiter.api.Test;

/**
 * Headless tests for {@link ConsumeItem} over a {@link FakeContext}'s {@link FakeConsumer}:
 * begin-then-RUNNING on tick one (a refused begin is an immediate first-tick FAILED), state to
 * status on later ticks, and the idempotent cancel contract. Mirrors {@link GoToTest}.
 */
class ConsumeItemTest {

    private final FakeContext ctx = new FakeContext();
    private final FakeConsumer consumer = ctx.consumer;

    /** Begin, don't read: the fake is pre-set to FINISHED and begin does not overwrite it, yet
     * tick one still reports RUNNING — state is consulted only from the second tick on. */
    @Test
    void firstTickBeginsAndReportsRunningWithoutReadingState() {
        consumer.setState(ConsumeState.FINISHED);
        ConsumeItem task = new ConsumeItem(14);
        assertEquals(TaskStatus.RUNNING, task.tick(ctx), "first tick begins, never reads state");
        assertEquals(1, consumer.beginCalls);
        assertEquals(14, consumer.lastSlot);
        assertEquals(TaskStatus.SUCCESS, task.tick(ctx), "second tick reads the state");
    }

    /** The refinement over GoTo: begin answers synchronously, so a refusal fails tick one. */
    @Test
    void refusedBeginFailsOnTheFirstTick() {
        consumer.beginResult = false;
        assertEquals(TaskStatus.FAILED, new ConsumeItem(3).tick(ctx),
                "empty/inedible slot: nothing started, an immediate, clean failure");
        assertEquals(1, consumer.beginCalls);
    }

    @Test
    void beginIsIssuedExactlyOnceAcrossManyTicks() {
        ConsumeItem task = new ConsumeItem(7);
        task.tick(ctx);
        consumer.setState(ConsumeState.CONSUMING);
        for (int i = 0; i < 5; i++) {
            assertEquals(TaskStatus.RUNNING, task.tick(ctx));
        }
        assertEquals(1, consumer.beginCalls, "the bite is begun once; later ticks only observe");
    }

    @Test
    void succeedsWhenTheConsumerFinishes() {
        ConsumeItem task = new ConsumeItem(7);
        task.tick(ctx);
        consumer.setState(ConsumeState.CONSUMING);
        assertEquals(TaskStatus.RUNNING, task.tick(ctx));
        consumer.setState(ConsumeState.FINISHED);
        assertEquals(TaskStatus.SUCCESS, task.tick(ctx));
    }

    @Test
    void failsWhenTheConsumerFails() {
        ConsumeItem task = new ConsumeItem(7);
        task.tick(ctx);
        consumer.setState(ConsumeState.FAILED);
        assertEquals(TaskStatus.FAILED, task.tick(ctx));
    }

    /** IDLE after a successful begin means the bite was stopped: no claiming an unfinished meal. */
    @Test
    void failsWhenTheConsumerIsUnexpectedlyIdle() {
        ConsumeItem task = new ConsumeItem(7);
        task.tick(ctx); // begin succeeded; the dumb fake's state is still IDLE
        assertEquals(TaskStatus.FAILED, task.tick(ctx));
    }

    @Test
    void cancelAbortsAndDoubleCancelIsSafe() {
        ConsumeItem task = new ConsumeItem(7);
        task.tick(ctx);
        task.cancel(ctx);
        assertEquals(1, consumer.abortCalls);
        assertEquals(ConsumeState.IDLE, consumer.state());
        task.cancel(ctx); // idempotent: absorbed by ItemConsumer.abort's safe-when-idle contract
        assertEquals(2, consumer.abortCalls);
    }

    @Test
    void cancelBeforeTheFirstTickIsSafe() {
        ConsumeItem task = new ConsumeItem(7);
        task.cancel(ctx);
        assertEquals(1, consumer.abortCalls);
        assertEquals(0, consumer.beginCalls, "cancelling an unstarted task begins no bite");
    }

    @Test
    void describeReadsAsConsumeSlot() {
        assertEquals("consume slot 14", new ConsumeItem(14).describe());
    }
}
