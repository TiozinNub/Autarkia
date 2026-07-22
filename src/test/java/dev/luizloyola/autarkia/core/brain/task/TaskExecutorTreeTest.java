package dev.luizloyola.autarkia.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.autarkia.core.brain.BrainContext;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * Headless tests for the {@link TaskExecutor}'s step-3 tree machinery: method selection,
 * sequences, failure and re-selection, trivial success, nesting, the
 * {@link TaskExecutor#MAX_DEPTH} guard, preemption and cancel. Scripted fakes only — the slot
 * lifecycle with a primitive root stays covered by {@link TaskExecutorTest}.
 */
class TaskExecutorTreeTest {

    private final FakeContext ctx = new FakeContext();
    private final TaskExecutor executor = new TaskExecutor();
    /** Shared ordered log every scripted node writes into — the sequencing truth of each test. */
    private final List<String> log = new ArrayList<>();

    // --- scripted tree pieces --------------------------------------------------------------------

    /** A primitive that runs {@code runningTicks} ticks, then reports its scripted terminal. */
    private static final class ScriptedPrimitive implements PrimitiveTask {
        private final String name;
        private final TaskStatus terminal;
        private int runningTicks;
        private final List<String> log;

        ScriptedPrimitive(String name, TaskStatus terminal, int runningTicks, List<String> log) {
            this.name = name;
            this.terminal = terminal;
            this.runningTicks = runningTicks;
            this.log = log;
        }

        @Override
        public TaskStatus tick(BrainContext ctx) {
            log.add("tick " + name);
            return runningTicks-- > 0 ? TaskStatus.RUNNING : terminal;
        }

        @Override
        public void cancel(BrainContext ctx) {
            log.add("cancel " + name);
        }

        @Override
        public String describe() {
            return name;
        }
    }

    /** A method with settable applicability/cost, counting cost reads, decomposing via a supplier. */
    private static final class FakeMethod implements Method {
        private final String name;
        boolean applicable = true;
        double cost;
        int costReads;
        private final Supplier<List<Task>> decomposition;

        FakeMethod(String name, double cost, Supplier<List<Task>> decomposition) {
            this.name = name;
            this.cost = cost;
            this.decomposition = decomposition;
        }

        @Override
        public boolean applicable(BrainContext ctx) {
            return applicable;
        }

        @Override
        public double estimateCost(BrainContext ctx) {
            costReads++;
            return cost;
        }

        @Override
        public List<Task> decompose(BrainContext ctx) {
            return decomposition.get();
        }

        @Override
        public String describe() {
            return name;
        }
    }

    private record FakeCompound(String name, List<Method> methodList) implements CompoundTask {
        @Override
        public List<Method> methods() {
            return methodList;
        }

        @Override
        public String describe() {
            return name;
        }
    }

    private ScriptedPrimitive prim(String name, TaskStatus terminal) {
        return new ScriptedPrimitive(name, terminal, 0, log);
    }

    private ScriptedPrimitive prim(String name, TaskStatus terminal, int runningTicks) {
        return new ScriptedPrimitive(name, terminal, runningTicks, log);
    }

    private static FakeCompound goal(String name, Method... methods) {
        return new FakeCompound(name, List.of(methods));
    }

    // --- method selection ------------------------------------------------------------------------

    @Test
    void cheapestApplicableMethodWins() {
        FakeMethod expensive = new FakeMethod("expensive way", 5.0, () -> List.of(prim("a", TaskStatus.SUCCESS)));
        FakeMethod cheap = new FakeMethod("cheap way", 2.0, () -> List.of(prim("b", TaskStatus.SUCCESS)));
        executor.run(goal("goal", expensive, cheap), ctx);
        executor.tick(ctx);
        assertEquals(List.of("tick b"), log, "the cheaper method's primitive runs; the other never expands");
        assertEquals("idle (last: goal -> SUCCESS)", executor.describe());
    }

    @Test
    void inapplicableMethodsAreSkippedWithoutEvenBeingCosted() {
        FakeMethod expensive = new FakeMethod("expensive way", 5.0, () -> List.of(prim("a", TaskStatus.SUCCESS)));
        FakeMethod cheapButOff = new FakeMethod("cheap way", 1.0, () -> List.of(prim("b", TaskStatus.SUCCESS)));
        cheapButOff.applicable = false;
        executor.run(goal("goal", expensive, cheapButOff), ctx);
        executor.tick(ctx);
        assertEquals(List.of("tick a"), log, "the only applicable method wins regardless of cost");
        assertEquals(0, cheapButOff.costReads, "inapplicable methods are never costed");
    }

    /**
     * Re-selection after a failure must re-read costs against the CURRENT context, never reuse
     * the first selection's scores.
     */
    @Test
    void costsAreRereadNotCachedOnReselectionAfterFailure() {
        FakeMethod doomed = new FakeMethod("doomed way", 1.0, () -> List.of(prim("a", TaskStatus.FAILED, 1)));
        FakeMethod second = new FakeMethod("second way", 2.0, () -> List.of(prim("b", TaskStatus.SUCCESS)));
        FakeMethod third = new FakeMethod("third way", 3.0, () -> List.of(prim("c", TaskStatus.SUCCESS)));
        executor.run(goal("goal", doomed, second, third), ctx);
        executor.tick(ctx); // selects doomed (1 < 2 < 3); its primitive still RUNNING
        third.cost = 0.0;   // third is now the cheapest survivor
        executor.tick(ctx); // primitive FAILS -> doomed tried -> re-select among second/third
        executor.tick(ctx);
        assertEquals(List.of("tick a", "tick a", "tick c"), log,
                "fresh scoring picks third; a cached score would have picked second");
        assertEquals(2, second.costReads, "second was scored at both selections");
        assertEquals(2, third.costReads, "third was scored at both selections");
        assertEquals("idle (last: goal -> SUCCESS)", executor.describe());
    }

    // --- sequences -------------------------------------------------------------------------------

    @Test
    void sequenceRunsSubtasksInOrderOnePrimitivePerTick() {
        FakeMethod way = new FakeMethod("way", 0.0,
                () -> List.of(prim("a", TaskStatus.SUCCESS, 1), prim("b", TaskStatus.SUCCESS)));
        executor.run(goal("goal", way), ctx);
        executor.tick(ctx); // a RUNNING
        executor.tick(ctx); // a SUCCESS — b must not be ticked on the same executor tick
        assertEquals(List.of("tick a", "tick a"), log);
        assertTrue(executor.isBusy());
        assertEquals("running: goal > way > b", executor.describe());
        executor.tick(ctx); // b SUCCESS -> sequence exhausted -> compound (root) SUCCESS
        assertEquals(List.of("tick a", "tick a", "tick b"), log);
        assertEquals("idle (last: goal -> SUCCESS)", executor.describe());
    }

    @Test
    void failedSubtaskFailsTheMethodAndTheNextMethodExpands() {
        FakeMethod doomed = new FakeMethod("doomed way", 0.0,
                () -> List.of(prim("a", TaskStatus.SUCCESS), prim("b", TaskStatus.FAILED)));
        FakeMethod fallback = new FakeMethod("fallback way", 1.0, () -> List.of(prim("c", TaskStatus.SUCCESS)));
        executor.run(goal("goal", doomed, fallback), ctx);
        executor.tick(ctx); // a SUCCESS
        executor.tick(ctx); // b FAILED -> doomed marked tried -> fallback expands
        executor.tick(ctx); // c SUCCESS -> root SUCCESS
        assertEquals(List.of("tick a", "tick b", "tick c"), log,
                "the failed method's progress is discarded; the fallback starts fresh");
        assertEquals("idle (last: goal -> SUCCESS)", executor.describe());
    }

    @Test
    void allMethodsExhaustedFailTheCompoundAtRootAndAreRecorded() {
        FakeMethod first = new FakeMethod("first way", 0.0, () -> List.of(prim("a", TaskStatus.FAILED)));
        FakeMethod second = new FakeMethod("second way", 1.0, () -> List.of(prim("b", TaskStatus.FAILED)));
        executor.run(goal("goal", first, second), ctx);
        executor.tick(ctx); // a FAILED -> first tried -> second expands
        executor.tick(ctx); // b FAILED -> second tried -> nothing left -> root FAILED
        assertEquals(List.of("tick a", "tick b"), log);
        assertFalse(executor.isBusy());
        assertEquals("idle (last: goal -> FAILED)", executor.describe());
    }

    @Test
    void compoundWithNoApplicableMethodFailsWithoutTickingAnything() {
        FakeMethod off = new FakeMethod("off way", 0.0, () -> List.of(prim("a", TaskStatus.SUCCESS)));
        off.applicable = false;
        executor.run(goal("goal", off), ctx);
        executor.tick(ctx);
        assertEquals(List.of(), log, "nothing applicable: the compound fails during expansion");
        assertEquals("idle (last: goal -> FAILED)", executor.describe());
    }

    // --- trivial success (empty decompose) -------------------------------------------------------

    @Test
    void emptyDecomposeTriviallySucceedsTheCompound() {
        FakeMethod already = new FakeMethod("already done", 0.0, List::of);
        executor.run(goal("goal", already), ctx);
        executor.tick(ctx);
        assertEquals(List.of(), log, "no primitive exists, none is ticked");
        assertEquals("idle (last: goal -> SUCCESS)", executor.describe());
    }

    @Test
    void trivialCompoundInASequenceDoesNotBurnATick() {
        FakeCompound trivial = goal("trivial", new FakeMethod("already done", 0.0, List::of));
        FakeMethod way = new FakeMethod("way", 0.0, () -> List.of(trivial, prim("a", TaskStatus.SUCCESS)));
        executor.run(goal("goal", way), ctx);
        executor.tick(ctx); // trivial cascades during descent; a is reached and ticked same tick
        assertEquals(List.of("tick a"), log);
        assertEquals("idle (last: goal -> SUCCESS)", executor.describe());
    }

    // --- nesting ---------------------------------------------------------------------------------

    @Test
    void nestedCompoundDescribesTheFullExpansionPath() {
        FakeCompound inner = goal("inner goal",
                new FakeMethod("inner way", 0.0, () -> List.of(prim("leaf", TaskStatus.SUCCESS, 100))));
        FakeCompound outer = goal("outer goal", new FakeMethod("outer way", 0.0, () -> List.of(inner)));
        executor.run(outer, ctx);
        assertEquals("running: outer goal", executor.describe(), "unexpanded root: just the goal");
        executor.tick(ctx); // expands both levels, ticks the leaf once (RUNNING)
        assertEquals(List.of("tick leaf"), log);
        assertEquals("running: outer goal > outer way > inner goal > inner way > leaf",
                executor.describe());
    }

    /** An inner compound running out of methods is a plain subtask failure to the outer method. */
    @Test
    void nestedFailureBubblesToTheOuterCompoundsNextMethod() {
        FakeCompound inner = goal("inner goal",
                new FakeMethod("inner way", 0.0, () -> List.of(prim("x", TaskStatus.FAILED))));
        FakeMethod viaInner = new FakeMethod("via inner", 0.0, () -> List.of(inner));
        FakeMethod direct = new FakeMethod("direct way", 1.0, () -> List.of(prim("c", TaskStatus.SUCCESS)));
        executor.run(goal("outer goal", viaInner, direct), ctx);
        executor.tick(ctx); // x FAILED -> inner way tried -> inner exhausted -> via inner tried -> direct expands
        executor.tick(ctx); // c SUCCESS -> root SUCCESS
        assertEquals(List.of("tick x", "tick c"), log);
        assertEquals("idle (last: outer goal -> SUCCESS)", executor.describe());
    }

    /**
     * Self-decomposing compound: {@link TaskExecutor#MAX_DEPTH} refuses the next expansion and the
     * refusal bubbles as a method failure through every level, so the root fails in one tick.
     */
    @Test
    void maxDepthExceededFailsTheBranchInsteadOfHanging() {
        executor.run(new RecursiveCompound(), ctx);
        executor.tick(ctx);
        assertFalse(executor.isBusy());
        assertEquals("idle (last: recursive goal -> FAILED)", executor.describe());
    }

    private static final class RecursiveCompound implements CompoundTask {
        @Override
        public List<Method> methods() {
            return List.of(new Method() {
                @Override
                public boolean applicable(BrainContext ctx) {
                    return true;
                }

                @Override
                public double estimateCost(BrainContext ctx) {
                    return 0.0;
                }

                @Override
                public List<Task> decompose(BrainContext ctx) {
                    return List.of(new RecursiveCompound()); // the cycle the guard must break
                }

                @Override
                public String describe() {
                    return "recurse";
                }
            });
        }

        @Override
        public String describe() {
            return "recursive goal";
        }
    }

    // --- preemption / cancel ---------------------------------------------------------------------

    @Test
    void cancelCancelsTheDeepestPrimitiveAndClearsTheStack() {
        FakeCompound inner = goal("inner goal",
                new FakeMethod("inner way", 0.0, () -> List.of(new GoTo(1, 2, 3))));
        executor.run(goal("outer goal", new FakeMethod("outer way", 0.0, () -> List.of(inner))), ctx);
        executor.tick(ctx); // the GoTo leaf issues its move
        assertEquals(1, ctx.mover.moveToCalls);
        executor.cancel(ctx);
        assertEquals(1, ctx.mover.stopCalls, "the deepest primitive held the legs; cancel releases them");
        assertFalse(executor.isBusy());
        assertEquals("idle", executor.describe(), "cancel records nothing");
    }

    @Test
    void preemptionCancelsTheDeepestPrimitiveBeforeTheNewcomerActs() {
        FakeCompound inner = goal("inner goal",
                new FakeMethod("inner way", 0.0, () -> List.of(new GoTo(1, 2, 3))));
        executor.run(goal("outer goal", new FakeMethod("outer way", 0.0, () -> List.of(inner))), ctx);
        executor.tick(ctx); // leaf issues its move
        executor.run(new GoTo(4, 5, 6), ctx);
        executor.tick(ctx); // newcomer issues its move
        assertEquals(List.of("moveTo(1, 2, 3)", "stop", "moveTo(4, 5, 6)"), ctx.mover.events,
                "released, then claimed — same ordering rule as step 2, now through the tree");
    }

    @Test
    void cancelWithAnUnexpandedCompoundCurrentIsQuiet() {
        FakeCompound inner = goal("inner goal",
                new FakeMethod("inner way", 0.0, () -> List.of(prim("b", TaskStatus.SUCCESS))));
        FakeMethod way = new FakeMethod("way", 0.0, () -> List.of(prim("a", TaskStatus.SUCCESS), inner));
        executor.run(goal("goal", way), ctx);
        executor.tick(ctx); // a SUCCESS; cursor now on the unexpanded inner compound
        executor.cancel(ctx);
        assertEquals(List.of("tick a"), log, "no cancel lands anywhere: a finished, inner never expanded");
        assertFalse(executor.isBusy());
        assertEquals("idle", executor.describe());
    }
}
