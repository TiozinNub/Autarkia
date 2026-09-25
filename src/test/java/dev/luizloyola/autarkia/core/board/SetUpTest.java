package dev.luizloyola.autarkia.core.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.board.WorkItem;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.FakeContext;
import dev.luizloyola.anima.core.brain.task.PlaceStation;
import dev.luizloyola.anima.core.inv.ItemCall;
import dev.luizloyola.anima.core.store.Store;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Stations go down one at a time and in order, each as the one item on offer: the whole of what
 * stops two members building the same one.
 */
class SetUpTest {

    private static final Pos YARD = new Pos(10, 64, 10);

    private static SetUp base() {
        return new SetUp(List.of(SetUp.WORKBENCH, SetUp.STORE), YARD, 0.5);
    }

    @Test
    void oneStationIsOnOfferAtATimeInOrder() {
        SetUp setUp = base();
        FakeContext ctx = new FakeContext();

        WorkItem bench = setUp.open().get(0);
        assertEquals(1, setUp.open().size());
        PlaceStation root = assertInstanceOf(PlaceStation.class, bench.root());
        assertEquals(SetUp.WORKBENCH.itemId(), root.itemId());
        assertEquals(YARD, root.near());

        setUp.completed(bench, ctx);
        WorkItem chest = setUp.open().get(0);
        assertEquals(Store.ITEM_ID, ((PlaceStation) chest.root()).itemId(),
                "the chest goes on offer only once the workbench is down");

        setUp.completed(chest, ctx);
        assertTrue(setUp.finished());
        assertTrue(setUp.open().isEmpty());
    }

    @Test
    void theStationIsTheItemsKitNeed() {
        List<ItemCall> kit = base().open().get(0).kit().calls();
        assertEquals(1, kit.size());
        assertEquals(ItemCall.Strength.NEED, kit.get(0).strength());
        assertTrue(kit.get(0).spec().matches(SetUp.WORKBENCH.itemId()),
                "kit, not cargo: a carried workbench is not put away before it is put down");
    }

    @Test
    void aMemberWhoFailedIsPacedAndNobodyElseIs() {
        SetUp setUp = base();
        FakeContext ctx = new FakeContext();
        ctx.percepts.time = 1_000L;
        AgentId juno = AgentId.random();
        AgentId wren = AgentId.random();
        WorkItem bench = setUp.open().get(0);

        setUp.failed(bench, juno, ctx);
        setUp.tick(1_100L);
        assertFalse(setUp.offerableTo(bench, juno, ctx));
        assertTrue(setUp.offerableTo(bench, wren, ctx));

        setUp.tick(1_000L + SetUp.FAIL_COOLDOWN);
        assertTrue(setUp.offerableTo(bench, juno, ctx));
    }

    @Test
    void aReloadPicksUpAtTheStationItWasOn() {
        SetUp setUp = base();
        setUp.completed(setUp.open().get(0), new FakeContext());
        WorkKey key = setUp.keyOf(setUp.open().get(0)).orElseThrow();

        SetUp back = SetUp.restore((SetUp.State) setUp.snapshot(), 0L).orElseThrow();

        assertEquals(List.of(SetUp.STORE), back.remaining());
        assertSame(back.open().get(0), back.itemFor(key).orElseThrow(),
                "a saved hold on the chest finds the chest again");
    }
}
