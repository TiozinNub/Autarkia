package dev.luizloyola.autarkia.core.board;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.sense.BeingId;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.FakeContext;
import dev.luizloyola.anima.core.brain.task.FakePercepts;
import dev.luizloyola.anima.core.brain.task.GoTo;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Straight to a leader in sight; else back to where the search set out. */
class ReturnAndTellTest {

    private final FakeContext ctx = new FakeContext();
    private final BeingId leader = BeingId.of(AgentId.random());

    @Test
    void aLeaderInSightIsGoneUpToAtOnce() {
        ctx.percepts.beings = List.of(FakePercepts.personAt(leader, new Pos(6, 64, 0), 6.0, "Luiz"));
        ReturnAndTell tell = new ReturnAndTell(new Pos(100, 64, 100), leader);
        assertTrue(tell.methods().get(0).applicable(ctx));
    }

    @Test
    void outOfSightItGoesBackToWhereTheSearchSetOut() {
        ReturnAndTell tell = new ReturnAndTell(new Pos(100, 64, 100), leader);
        assertFalse(tell.methods().get(0).applicable(ctx));
        assertTrue(tell.methods().get(1).applicable(ctx));
        assertInstanceOf(GoTo.class, tell.methods().get(1).decompose(ctx).get(0));
        assertFalse(new ReturnAndTell(null, leader).methods().get(1).applicable(ctx), "nowhere known to go back to");
    }

    @Test
    void comingBackIsNeverPricedOut() {
        ReturnAndTell tell = new ReturnAndTell(new Pos(5000, 64, 5000), leader);
        org.junit.jupiter.api.Assertions.assertEquals(0.0, tell.methods().get(1).estimateCost(ctx));
    }
}
