package dev.luizloyola.autarkia.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Pins {@link Idle}: exactly {@code ticks} RUNNING reports then SUCCESS, a no-op cancel, and the
 * describe shape. The wander pause rides on this, so the count must be exact.
 */
class IdleTest {

    private final FakeContext ctx = new FakeContext();

    @Test
    void runsForExactlyTheGivenTicksThenSucceeds() {
        Idle idle = new Idle(3);
        assertEquals(TaskStatus.RUNNING, idle.tick(ctx));
        assertEquals(TaskStatus.RUNNING, idle.tick(ctx));
        assertEquals(TaskStatus.RUNNING, idle.tick(ctx)); // the 3rd (last) RUNNING
        assertEquals(TaskStatus.SUCCESS, idle.tick(ctx)); // the 4th tick completes it
    }

    @Test
    void zeroTicksSucceedsImmediately() {
        assertEquals(TaskStatus.SUCCESS, new Idle(0).tick(ctx));
    }

    @Test
    void cancelIsANoOpAndTouchesNoActuator() {
        Idle idle = new Idle(5);
        idle.tick(ctx);
        idle.cancel(ctx); // must not throw, must not touch the body
        assertEquals(0, ctx.mover.stopCalls);
        assertEquals(0, ctx.consumer.abortCalls);
        // cancel does not end the task on its own terms; ticking still counts down from where it was
        assertEquals(TaskStatus.RUNNING, idle.tick(ctx));
    }

    @Test
    void describeShowsTheOriginalDuration() {
        Idle idle = new Idle(40);
        assertEquals("idle 40t", idle.describe());
        idle.tick(ctx); // stable across ticks — describe reflects the configured duration, not the remaining
        assertEquals("idle 40t", idle.describe());
    }
}
