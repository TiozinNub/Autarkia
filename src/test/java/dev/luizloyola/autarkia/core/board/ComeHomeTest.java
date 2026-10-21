package dev.luizloyola.autarkia.core.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.board.WorkItem;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.FakeContext;
import dev.luizloyola.anima.core.brain.task.GoTo;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A newcomer's walk home: offered to that newcomer alone, once, and kept across a restart. */
class ComeHomeTest {

    private final AgentId bram = AgentId.random();
    private final AgentId eli = AgentId.random();
    private final FakeContext ctx = new FakeContext();

    @Test
    void eachNewcomerIsOfferedOnlyTheirOwnWalkHome() {
        ComeHome project = new ComeHome(new Pos(10, 64, -40), List.of(bram, eli), 0.5);
        WorkItem bramsWalk = project.open().get(0);

        assertTrue(project.offerableTo(bramsWalk, bram, ctx));
        assertFalse(project.offerableTo(bramsWalk, eli, ctx));
        GoTo walk = assertInstanceOf(GoTo.class, bramsWalk.root());
        assertEquals(-40, walk.z());
    }

    @Test
    void aWalkDoneOrFailedIsNotOfferedAgainAndTheLastEndsIt() {
        ComeHome project = new ComeHome(new Pos(0, 64, 0), List.of(bram, eli), 0.5);
        project.completed(project.open().get(0), ctx);
        project.failed(project.open().get(0), ctx);
        assertTrue(project.open().isEmpty());
        assertTrue(project.finished());
    }

    @Test
    void whoIsStillToComeSurvivesASave() {
        ComeHome project = new ComeHome(new Pos(3, 64, 4), List.of(bram, eli), 0.5);
        project.completed(project.open().get(0), ctx);

        ComeHome back = assertInstanceOf(ComeHome.class, ComeHome.TYPE.restore(project.snapshot(), 0).orElseThrow());
        assertEquals(1, back.open().size());
        assertTrue(back.offerableTo(back.open().get(0), eli, ctx));
        assertTrue(back.itemFor(new WorkKey.ForMember(ComeHome.FLAVOUR, eli)).isPresent());
    }
}
