package dev.luizloyola.autarkia.core.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.board.WorkItem;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.FakeContext;
import dev.luizloyola.anima.core.brain.task.Task;
import dev.luizloyola.anima.core.brain.task.UnloadFurnace;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Coming back to a furnace: the starter first, anybody after the grace, as the next job. */
class TendTest {

    private final AgentId ana = AgentId.random();
    private final AgentId bia = AgentId.random();
    private final Pos furnace = new Pos(4, 64, 0);
    private final FakeContext ctx = new FakeContext();

    private Tend dueAt(long due) {
        return new Tend(furnace, "minecraft:charcoal", Stock.PLANKS, true, ana, due);
    }

    @Test
    void theStarterIsOfferedItFirstAndAnybodyAfterTheGrace() {
        Tend tend = dueAt(1000L);
        tend.tick(1000L);
        WorkItem item = tend.open().get(0);

        assertTrue(tend.offerableTo(item, ana, ctx));
        assertFalse(tend.offerableTo(item, bia, ctx), "Ana set it going");
        tend.tick(1000L + Tend.GRACE + 40);
        assertTrue(tend.offerableTo(item, bia, ctx), "Ana did not come in time");
    }

    @Test
    void itIsNextInLineAboveThePartysOtherWork() {
        assertTrue(Tend.PRIORITY > 0.5, "over every Direction's work");
        assertTrue(Tend.PRIORITY < 0.6, "under a hungry body's eating");
    }

    @Test
    void theTripTakesItOutRefuelsAndCarriesItHome() {
        TendErrand errand = assertInstanceOf(TendErrand.class, dueAt(0L).open().get(0).root());
        List<Task> steps = errand.methods().get(0).decompose(ctx);

        UnloadFurnace unload = assertInstanceOf(UnloadFurnace.class, steps.get(0));
        assertEquals(furnace, unload.at());
        assertEquals(Stock.PLANKS, unload.fuel());
        assertInstanceOf(BringBack.class, steps.get(1));
    }

    @Test
    void aFailureSitsThatMemberOutAWhile() {
        Tend tend = dueAt(0L);
        WorkItem item = tend.open().get(0);
        ctx.percepts.time = 100L;
        tend.failed(item, ana, ctx);
        tend.tick(200L);
        assertFalse(tend.offerableTo(item, ana, ctx));
        tend.tick(100L + SetUp.FAIL_COOLDOWN + 1);
        assertTrue(tend.offerableTo(item, ana, ctx));
    }

    @Test
    void itSurvivesARestartHalfwayThroughTheGrace() {
        Tend tend = dueAt(1000L);
        tend.tick(1300L);
        Tend back = Tend.restore((Tend.State) tend.snapshot(), 5000L).orElseThrow();
        WorkItem item = back.open().get(0);

        assertFalse(back.offerableTo(item, bia, ctx), "the clock it had, not the restore's");
        assertEquals(ana, back.starter());
        assertEquals(1000L, back.dueAt());
    }

    @Test
    void doneIsDone() {
        Tend tend = dueAt(0L);
        tend.completed(tend.open().get(0), ctx);
        assertTrue(tend.finished());
        assertTrue(tend.open().isEmpty());
    }
}
