package dev.luizloyola.autarkia.core.board;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.sense.BeingId;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.FakeContext;
import dev.luizloyola.anima.core.brain.task.FakePercepts;
import dev.luizloyola.anima.core.brain.task.Method;
import dev.luizloyola.anima.core.brain.task.Producers;
import dev.luizloyola.anima.core.brain.task.Task;
import dev.luizloyola.anima.core.brain.task.TaskStatus;
import dev.luizloyola.anima.core.inv.ItemSpec;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TravelWithTest {

    private static final ItemSpec STONE = ItemSpec.register(
            new ItemSpec("travel-with-test-stone", id -> id.startsWith("test:stone")));
    private static final BeingId LEADER = BeingId.of(UUID.randomUUID());

    private final FakeContext ctx = new FakeContext();
    /** How far off the one source this body knows is, in walk-blocks. */
    private static double sourceAt = Double.POSITIVE_INFINITY;

    // Registered once and never reset: Producers.reset() also drops what other classes register
    // when they load, and StrandedDigTest failed for it.
    static {
        Producers.register(STONE, id -> true, wanted -> new Method() {
            @Override
            public boolean applicable(BrainContext c) {
                return sourceAt < Double.POSITIVE_INFINITY;
            }

            @Override
            public double estimateCost(BrainContext c) {
                return sourceAt;
            }

            @Override
            public List<Task> decompose(BrainContext c) {
                return List.of();
            }

            @Override
            public String describe() {
                return "mine the stone";
            }
        });
    }

    @BeforeEach
    void setUp() {
        sourceAt = Double.POSITIVE_INFINITY;
        ctx.percepts.position = new Pos(0, 64, 0);
        ctx.depot = java.util.Optional.of(new dev.luizloyola.anima.core.store.Depot.Site(new Pos(0, 64, 0),
                Set.of(new dev.luizloyola.anima.core.territory.ChunkKey(
                        dev.luizloyola.anima.core.territory.ChunkKey.OVERWORLD, 0, 0))));
    }

    private void leaderAt(Pos where) {
        ctx.percepts.beings = List.of(FakePercepts.animalAt(LEADER, "person", where, 3));
    }

    @Test
    void itKeepsWithTheLeaderUntilItKnowsTheSourceWithinAnOrdinaryBudget() {
        TravelWith travel = new TravelWith(LEADER, STONE, Set.of("minecraft:furnace"));
        leaderAt(new Pos(3, 64, 0));
        sourceAt = 220;

        assertEquals(TaskStatus.RUNNING, travel.tick(ctx), "known, but past any ordinary errand's walk");
        sourceAt = 30;
        TaskStatus last = TaskStatus.RUNNING;
        for (int i = 0; i < TravelWith.LOOK_EVERY && last == TaskStatus.RUNNING; i++) {
            last = travel.tick(ctx);
        }
        assertEquals(TaskStatus.SUCCESS, last, "near enough to fetch its own share");
    }

    @Test
    void itFailsWhenTheLeaderIsLostFromSight() {
        TravelWith travel = new TravelWith(LEADER, STONE, Set.of());
        assertEquals(TaskStatus.FAILED, travel.tick(ctx));
    }

    @Test
    void itFailsWhenTheLeaderGoesNowhere() {
        TravelWith travel = new TravelWith(LEADER, STONE, Set.of());
        leaderAt(new Pos(3, 64, 0));
        TaskStatus last = TaskStatus.RUNNING;
        for (int i = 0; i < TravelWith.STALL_TICKS + 1 && last == TaskStatus.RUNNING; i++) {
            last = travel.tick(ctx);
        }
        assertEquals(TaskStatus.FAILED, last, "a muster and a kit-up are past; nobody is leading");
    }
}
