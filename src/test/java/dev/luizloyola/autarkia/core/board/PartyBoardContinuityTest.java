package dev.luizloyola.autarkia.core.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.JsonOps;
import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.board.WorkItem;
import dev.luizloyola.anima.core.continuity.StateGraph;
import dev.luizloyola.anima.core.social.PartyId;
import dev.luizloyola.autarkia.mod.board.PartyBoardCodecs;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A party's board saved and loaded through the store's own codecs comes back as it was: the
 * projects, who holds what until when, the {@code #n} handles, and who is failing or stood down
 * (continuity, 2026-09-26). A fresh TTL for every hold, renumbered handles and a forgiven bench
 * were each a restart an observer could see.
 */
class PartyBoardContinuityTest {

    private static final PartyId PARTY = PartyId.of(UUID.randomUUID());

    @BeforeEach
    void wireTheRegistries() {
        Splits.register(CarrySplit.INSTANCE);
        PartyProjects.register(Gather.TYPE);
    }

    private static PartyBoard reloaded(PartyBoard live, long now) {
        var rows = PartyBoardCodecs.ROW.listOf();
        List<PartyBoard.Row> saved = rows.parse(JsonOps.INSTANCE,
                rows.encodeStart(JsonOps.INSTANCE, live.snapshot(now)).getOrThrow()).getOrThrow();
        Board.Pacing pacing = PartyBoardCodecs.PACING.parse(JsonOps.INSTANCE,
                PartyBoardCodecs.PACING.encodeStart(JsonOps.INSTANCE, live.pacing(now)).getOrThrow())
                .getOrThrow();
        PartyBoard back = new PartyBoard(PARTY);
        back.restorePacing(pacing);
        assertEquals(0, back.restore(saved, now));
        return back;
    }

    @Test
    void aBoardComesBackAsItWas() {
        AgentId walker = AgentId.random();
        AgentId flailer = AgentId.random();
        AgentId benched = AgentId.random();
        PartyBoard live = new PartyBoard(PARTY);
        live.post(new Gather(Stock.LOGS, 1, 0.5, PARTY, CarrySplit.INSTANCE));
        live.post(new Gather(Stock.LOGS, 64, 0.5, PARTY, CarrySplit.INSTANCE));
        live.tick(0L);
        Gather gather = (Gather) live.projects().get(live.projects().size() - 1);
        BoardBrainContext ctx = new BoardBrainContext();
        WorkItem trip = gather.realise(gather.open().get(0), walker, ctx);
        assertTrue(live.claim(trip, walker, 100L));
        live.restorePacing(new Board.Pacing(0, Map.of(flailer, 2), Map.of(benched, 900L)));

        long now = 400L; // the lease has run a while: it has less than a fresh TTL left
        PartyBoard back = reloaded(live, now);

        assertEquals(List.of(), StateGraph.capture(live).diff(StateGraph.capture(back)));
        assertEquals(live.handleOf(gather).orElseThrow(),
                back.handleOf(back.projects().get(back.projects().size() - 1)).orElseThrow(),
                "the #n an operator refers to");
        assertTrue(back.isBenched(benched, now), "a stood-down member stays stood down");
    }
}
