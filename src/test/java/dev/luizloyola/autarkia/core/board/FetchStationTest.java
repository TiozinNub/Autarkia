package dev.luizloyola.autarkia.core.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.FakeContext;
import dev.luizloyola.anima.core.brain.task.Task;
import dev.luizloyola.anima.core.inv.ItemStack;
import dev.luizloyola.anima.core.store.Store;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A store left far from a new home: fetched by one who brought it, then let go (decision 21). */
class FetchStationTest {

    private final FakeContext ctx = new FakeContext();
    private final AgentId bram = AgentId.random();
    private final Pos far = new Pos(300, 64, 300);

    @Test
    void onlyThoseWhoBroughtItAreOfferedTheFetch() {
        FetchStation project = new FetchStation(far, List.of(bram), 0.45);
        assertTrue(project.offerableTo(project.open().get(0), bram, ctx));
        assertFalse(project.offerableTo(project.open().get(0), AgentId.random(), ctx));
    }

    @Test
    void doneOrNotTheStoreIsLetGo() {
        ctx.claim(Store.POI, far);
        FetchStation project = new FetchStation(far, List.of(bram), 0.45);
        project.failed(project.open().get(0), ctx);
        assertTrue(project.finished());
        assertTrue(ctx.knowledge().places().all(Store.POI).isEmpty(), "the far chest is nobody's now");
    }

    @Test
    void itSurvivesASave() {
        FetchStation project = new FetchStation(far, List.of(bram), 0.45);
        FetchStation back = assertInstanceOf(FetchStation.class, FetchStation.TYPE.restore(project.snapshot(), 0).orElseThrow());
        assertEquals(far, back.at());
        assertTrue(back.offerableTo(back.open().get(0), bram, ctx));
    }

    @Test
    void onlyWhatTheTakeAddedGoesHome() {
        ctx.percepts.inventory.set(0, ItemStack.of("minecraft:stone_axe", 1, 1));
        ctx.percepts.inventory.set(1, ItemStack.of("minecraft:bread", 2, 64));
        FetchErrand.BringGained carry = new FetchErrand.BringGained(FetchErrand.counts(ctx.percepts.inventory));
        assertFalse(carry.methods().get(0).applicable(ctx), "nothing taken yet, nothing to carry");

        ctx.percepts.inventory.set(1, ItemStack.of("minecraft:bread", 7, 64));
        ctx.percepts.inventory.set(2, ItemStack.of("minecraft:oak_log", 12, 64));
        List<Task> steps = carry.methods().get(0).decompose(ctx);

        BringBack bring = assertInstanceOf(BringBack.class, steps.get(0));
        assertEquals(17, bring.count(), "five bread and twelve logs came out of the chest");
        assertTrue(bring.spec().matcher().test("minecraft:oak_log"));
        assertFalse(bring.spec().matcher().test("minecraft:stone_axe"), "the axe was its own");
    }

    @Test
    void gainedCountsOnlyWhatGrew() {
        assertEquals(Map.of("b", 3), FetchErrand.gained(Map.of("a", 2, "b", 1), Map.of("a", 1, "b", 4)));
    }
}
