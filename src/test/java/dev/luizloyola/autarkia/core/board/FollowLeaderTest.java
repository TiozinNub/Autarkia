package dev.luizloyola.autarkia.core.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.board.WorkItem;
import dev.luizloyola.anima.core.brain.task.FakeContext;
import dev.luizloyola.anima.core.brain.task.Follow;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Each follower keeps with the leader, pauses after catching up, and stops when the spot is named. */
class FollowLeaderTest {

    private final FakeContext ctx = new FakeContext();
    private final AgentId leader = AgentId.random();
    private final AgentId pia = AgentId.random();

    @Test
    void eachFollowerHasTheirOwnKeepingUp() {
        FollowLeader project = new FollowLeader(leader, List.of(pia), 0.6);
        WorkItem keep = project.open().get(0);
        assertTrue(project.offerableTo(keep, pia, ctx));
        assertFalse(project.offerableTo(keep, AgentId.random(), ctx));
        assertInstanceOf(Follow.class, keep.root());
    }

    @Test
    void caughtUpItPausesBeforeKeepingUpAgain() {
        FollowLeader project = new FollowLeader(leader, List.of(pia), 0.6);
        WorkItem keep = project.open().get(0);
        project.completed(keep, ctx);
        assertFalse(project.offerableTo(keep, pia, ctx));
        ctx.percepts.time = FollowLeader.CAUGHT_UP_PAUSE;
        assertTrue(project.offerableTo(keep, pia, ctx), "the leader moved off: follow again");
    }

    @Test
    void namingTheSpotEndsItAndThatSurvivesASave() {
        FollowLeader project = new FollowLeader(leader, List.of(pia), 0.6);
        project.end();
        assertTrue(project.finished());
        assertTrue(project.open().isEmpty());
        FollowLeader back = assertInstanceOf(FollowLeader.class, FollowLeader.TYPE.restore(project.snapshot(), 0).orElseThrow());
        assertTrue(back.finished());
        assertEquals(leader, back.leader());
    }
}
