package dev.luizloyola.autarkia.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.autarkia.core.brain.BrainContext;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The achieve-compound executor semantics: satisfied-is-the-terminator, fresh rounds on success
 * (tried-marks reset), failure exclusion within a round, and the zero-progress cap.
 */
class AchieveTaskTest {

    private final FakeContext ctx = new FakeContext();
    private final TaskExecutor executor = new TaskExecutor();
    /** The "world state" the scripted tasks mutate and the goal reads. */
    private int stock;

    /** A primitive that adds {@code yield} to the stock and succeeds instantly. */
    private final class Produce implements PrimitiveTask {
        final int yield;
        int runs;

        Produce(int yield) {
            this.yield = yield;
        }

        @Override
        public TaskStatus tick(BrainContext c) {
            runs++;
            stock += yield;
            return TaskStatus.SUCCESS;
        }

        @Override
        public void cancel(BrainContext c) {
        }

        @Override
        public String describe() {
            return "produce " + yield;
        }
    }

    private final class Fail implements PrimitiveTask {
        int runs;

        @Override
        public TaskStatus tick(BrainContext c) {
            runs++;
            return TaskStatus.FAILED;
        }

        @Override
        public void cancel(BrainContext c) {
        }

        @Override
        public String describe() {
            return "fail";
        }
    }

    private static Method way(String name, double cost, java.util.function.Supplier<List<Task>> decompose) {
        return new Method() {
            @Override
            public boolean applicable(BrainContext c) {
                return true;
            }

            @Override
            public double estimateCost(BrainContext c) {
                return cost;
            }

            @Override
            public List<Task> decompose(BrainContext c) {
                return decompose.get();
            }

            @Override
            public String describe() {
                return name;
            }
        };
    }

    private AchieveTask goal(int target, Method... methods) {
        return new AchieveTask() {
            @Override
            public boolean satisfied(BrainContext c) {
                return stock >= target;
            }

            @Override
            public double progress(BrainContext c) {
                return stock; // like ObtainItem: rounds that grow the pile refund the cap
            }

            @Override
            public List<Method> methods() {
                return List.of(methods);
            }

            @Override
            public String describe() {
                return "stock " + target;
            }
        };
    }

    private Optional<TaskStatus> drive(AchieveTask root, int maxTicks) {
        executor.run(root, ctx);
        for (int i = 0; i < maxTicks && executor.isBusy(); i++) {
            executor.tick(ctx);
        }
        return executor.lastStatus();
    }

    @Test
    void roundsRepeatUntilTheConditionHolds() {
        Produce one = new Produce(1);
        Optional<TaskStatus> status = drive(goal(3, way("make one", 1, () -> List.of(one))), 20);

        assertEquals(Optional.of(TaskStatus.SUCCESS), status);
        assertEquals(3, stock, "exactly the rounds needed, not one more");
        assertEquals(3, one.runs);
    }

    @Test
    void satisfiedAtEntrySucceedsWithoutRunningAnything() {
        stock = 5;
        Produce one = new Produce(1);
        Optional<TaskStatus> status = drive(goal(3, way("make one", 1, () -> List.of(one))), 5);

        assertEquals(Optional.of(TaskStatus.SUCCESS), status);
        assertEquals(0, one.runs, "achieved trivially — no frame, no work");
    }

    @Test
    void aFailedMethodIsExcludedForTheRoundButEligibleNextRound() {
        Fail cheapButBroken = new Fail();
        Produce one = new Produce(1);
        AchieveTask root = goal(2,
                way("broken", 1, () -> List.of(cheapButBroken)),
                way("works", 5, () -> List.of(one)));
        Optional<TaskStatus> status = drive(root, 30);

        assertEquals(Optional.of(TaskStatus.SUCCESS), status);
        assertEquals(2, one.runs);
        assertEquals(2, cheapButBroken.runs,
                "cheapest wins each FRESH round, burns, and the fallback finishes it — twice");
    }

    /**
     * The cap meters STALLS, not work: 39 trees felled flawlessly were killed by the
     * zero-progress backstop at round 32. A round that raises {@link AchieveTask#progress}
     * hands the budget back.
     */
    @Test
    void anHonestGrindLongerThanTheCapOutlivesIt() {
        int target = TaskExecutor.ACHIEVE_ROUNDS_CAP + 8;
        Produce one = new Produce(1);
        Optional<TaskStatus> status = drive(goal(target, way("make one", 1, () -> List.of(one))),
                target * 3);

        assertEquals(Optional.of(TaskStatus.SUCCESS), status,
                "every round earned its keep — the stall cap must not end productive work");
        assertEquals(target, one.runs);
    }

    @Test
    void zeroProgressHitsTheRoundsCapAndFails() {
        Produce nothing = new Produce(0);
        Optional<TaskStatus> status = drive(goal(1, way("spin", 1, () -> List.of(nothing))), 200);

        assertEquals(Optional.of(TaskStatus.FAILED), status);
        assertTrue(nothing.runs <= TaskExecutor.ACHIEVE_ROUNDS_CAP, "the backstop bounded it");
    }

    @Test
    void emptyDecomposeIsNoProgressForAnAchieveGoal() {
        Produce one = new Produce(1);
        AchieveTask root = goal(2,
                way("claims trivial success", 0, List::of),
                way("works", 5, () -> List.of(one)));
        Optional<TaskStatus> status = drive(root, 30);

        assertEquals(Optional.of(TaskStatus.SUCCESS), status,
                "the empty pick is burned, the real method achieves the goal");
        assertEquals(2, one.runs);
    }

    /**
     * A dying way keeps getting fresh rounds (the world may have changed) until the rounds cap
     * ends the frame. Failing on the first death froze a 2000-log stock on one unworkable tree.
     */
    @Test
    void aWayThatOnlyEverDiesFailsAtTheRoundsCapNotTheFirstDeath() {
        Fail broken = new Fail();
        Optional<TaskStatus> status = drive(goal(1, way("broken", 1, () -> List.of(broken))), 200);

        assertEquals(Optional.of(TaskStatus.FAILED), status);
        assertTrue(broken.runs > 1, "the first death alone no longer ends the goal");
        assertTrue(broken.runs <= TaskExecutor.ACHIEVE_ROUNDS_CAP + 1, "the cap bounded it");
    }

    /**
     * The freeze, closed: "obtain logs x2000" died on its first unworkable tree with 78 good ones
     * remembered. A way that fails and later works finishes the stock.
     */
    @Test
    void aFailedWayGetsFreshRoundsWhileTheWorldChanges() {
        var flaky = new PrimitiveTask() {
            int runs;

            @Override
            public TaskStatus tick(BrainContext c) {
                runs++;
                if (runs <= 2) {
                    return TaskStatus.FAILED; // two unworkable trees, failed outright
                }
                stock += 1;
                return TaskStatus.SUCCESS; // the third tree pays
            }

            @Override
            public void cancel(BrainContext c) {
            }

            @Override
            public String describe() {
                return "flaky chop";
            }
        };
        Optional<TaskStatus> status = drive(goal(1, way("chop", 1, () -> List.of(flaky))), 30);

        assertEquals(Optional.of(TaskStatus.SUCCESS), status);
        assertEquals(3, flaky.runs, "two dead trees, then the third one pays the stock");
    }

    @Test
    void failuresCarryTheirDeepestCause() {
        // A failing primitive: the origin survives the bubble (and the retry rounds).
        drive(goal(1, way("broken", 1, () -> List.of(new Fail()))), 200);
        assertTrue(executor.failureReason().orElseThrow().contains("fail failed"),
                "the deepest failing node names itself");

        // Everything applicable but unaffordable: the tolerance story is printable.
        ctx.costTolerance = 10;
        Produce one = new Produce(1);
        drive(goal(9, way("far away", 50, () -> List.of(one))), 10);
        assertTrue(executor.failureReason().orElseThrow().contains("priced out at tolerance 10"),
                "priced-out failures say so, with the budget");
        assertEquals(0, one.runs);
    }
}
