package dev.luizloyola.autarkia.core.person.speech;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.history.Slot;
import dev.luizloyola.anima.core.brain.sense.BeingId;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.FakeContext;
import dev.luizloyola.anima.core.social.GroupUp;
import dev.luizloyola.anima.core.social.GroupUps;
import dev.luizloyola.anima.core.social.speech.Chooser;
import dev.luizloyola.anima.core.social.speech.Encounter;
import dev.luizloyola.anima.core.social.speech.LineSlots;
import dev.luizloyola.anima.core.social.speech.Speech;
import dev.luizloyola.anima.core.social.speech.SpeechAct;
import dev.luizloyola.anima.core.social.speech.SpeechActs;
import dev.luizloyola.anima.core.social.speech.Utterance;
import dev.luizloyola.anima.core.store.Depot;
import dev.luizloyola.anima.core.territory.ChunkKey;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The ladder's three group-up rungs: answering an ask, asking, and saying where home is. */
class GroupUpRungsTest {

    private final PersonChooser chooser = new PersonChooser();
    private final FakeContext ctx = new FakeContext();
    private final BeingId otherId = BeingId.of(AgentId.random());
    private final AgentId other = otherId.asPerson();

    /** The rule as each test sets it. */
    private Optional<GroupUp.Ask> ask = Optional.empty();
    private Optional<String> refusal = Optional.empty();

    GroupUpRungsTest() {
        GroupUps.install(new GroupUp() {
            @Override
            public Optional<Ask> ask(AgentId self, AgentId whom) {
                return ask;
            }

            @Override
            public double seeking(AgentId self) {
                return 0;
            }

            @Override
            public boolean worthSeeking(AgentId self, AgentId whom) {
                return false;
            }

            @Override
            public Optional<String> refusal(AgentId self, AgentId asker, Ask kind) {
                return refusal;
            }

            @Override
            public Optional<Move> move(AgentId asker, AgentId asked, Ask kind) {
                return Optional.empty();
            }
        });
    }

    @AfterEach
    void uninstall() {
        GroupUps.install(GroupUp.NEVER);
    }

    private Encounter encounter() {
        return ctx.speech.join(otherId, Speech.Opening.QUIET).orElseThrow();
    }

    private Chooser.Turn turn(Encounter e, Optional<Utterance> pending, SpeechAct... applicable) {
        return new Chooser.Turn(e, List.of(applicable), pending, Optional.empty(), true, Optional.of(other));
    }

    @Test
    void askedToJoinItSaysYesWhenTheRuleHasNoReasonAgainst() {
        Utterance asked = new Utterance(other, SpeechActs.ASK_JOIN.key(), Map.of(), 0);
        Chooser.Turn turn = turn(encounter(), Optional.of(asked), SpeechActs.REFUSE_JOIN, SpeechActs.ACCEPT_JOIN);
        assertEquals(Chooser.Line.of(SpeechActs.ACCEPT_JOIN), chooser.choose(ctx, turn));
    }

    @Test
    void invitedItSaysNoAndWhy() {
        refusal = Optional.of("food");
        Utterance invited = new Utterance(other, SpeechActs.INVITE.key(), Map.of(), 0);
        Chooser.Turn turn = turn(encounter(), Optional.of(invited), SpeechActs.REFUSE_JOIN, SpeechActs.ACCEPT_JOIN);
        assertEquals(new Chooser.Line(SpeechActs.REFUSE_JOIN, Map.of(Utterance.TOPIC, "food")),
                chooser.choose(ctx, turn));
    }

    @Test
    void greetedItAsksWhatTheRuleAllowsOnceARecord() {
        ask = Optional.of(GroupUp.Ask.INVITE);
        Encounter e = encounter();
        Chooser.Turn turn = turn(e, Optional.empty(), SpeechActs.ASK_JOIN, SpeechActs.INVITE, SpeechActs.END_CHAT);
        assertEquals(Chooser.Line.of(SpeechActs.INVITE), chooser.choose(ctx, turn));

        e.append(new Utterance(ctx.self, SpeechActs.INVITE.key(), Map.of(), 1));
        e.append(new Utterance(other, SpeechActs.REFUSE_JOIN.key(), Map.of(), 2));
        assertNotEquals(Chooser.Line.of(SpeechActs.INVITE), chooser.choose(ctx, turn), "turned down here, not again");
    }

    @Test
    void theOneWhoseHomeItIsSaysWhereItIs() {
        ctx.depot = Optional.of(new Depot.Site(new Pos(0, 64, -120), Set.of(ChunkKey.at(ChunkKey.OVERWORLD, 0, -120))));
        ctx.colleagues.add(other);
        Encounter e = encounter();
        e.append(new Utterance(other, SpeechActs.ASK_JOIN.key(), Map.of(), 1));
        e.append(new Utterance(ctx.self, SpeechActs.ACCEPT_JOIN.key(), Map.of(), 2));

        Chooser.Line line = chooser.choose(ctx, turn(e, Optional.empty(), SpeechActs.INFORM_HOME, SpeechActs.END_CHAT));

        assertEquals(SpeechActs.INFORM_HOME, line.act());
        List<Slot> slots = LineSlots.of(line.payload()).orElseThrow();
        assertEquals(Slot.lang("anima.compass.north"), slots.get(0), "north is −z");
    }

    @Test
    void theOneWhoMovedDoesNotTellTheOthersWhereTheyLive() {
        ctx.depot = Optional.of(new Depot.Site(new Pos(0, 64, -120), Set.of(ChunkKey.at(ChunkKey.OVERWORLD, 0, -120))));
        ctx.colleagues.add(other);
        Encounter e = encounter();
        e.append(new Utterance(ctx.self, SpeechActs.ASK_JOIN.key(), Map.of(), 1));
        e.append(new Utterance(other, SpeechActs.ACCEPT_JOIN.key(), Map.of(), 2));

        Chooser.Line line = chooser.choose(ctx, turn(e, Optional.empty(), SpeechActs.INFORM_HOME, SpeechActs.END_CHAT));
        assertNotEquals(SpeechActs.INFORM_HOME, line == null ? null : line.act());
    }

    @Test
    void theCompassHasEightPoints() {
        assertEquals("north", PersonChooser.compass(0, -10));
        assertEquals("east", PersonChooser.compass(10, 0));
        assertEquals("southwest", PersonChooser.compass(-10, 10));
        assertEquals("northwest", PersonChooser.compass(-10, -9));
    }

    @Test
    void aPlaceFoundIsPutToTheLeaderFromWhereTheyStand() {
        dev.luizloyola.autarkia.core.board.Explore.install(new dev.luizloyola.autarkia.core.direction.HomeLooking() {
            @Override
            public Optional<Look> look(Pos at, dev.luizloyola.anima.core.social.PartyId party,
                    dev.luizloyola.anima.core.brain.knowledge.AgentKnowledge knowledge) {
                return Optional.empty();
            }

            @Override
            public boolean claim(dev.luizloyola.anima.core.social.PartyId party,
                    dev.luizloyola.autarkia.core.direction.HomeJudge.Candidate plot,
                    dev.luizloyola.anima.core.brain.knowledge.AgentKnowledge knowledge) {
                return false;
            }

            @Override
            public Optional<Pos> suggestion(AgentId self, AgentId leader) {
                return leader.equals(other) ? Optional.of(new Pos(100, 64, 0)) : Optional.empty();
            }
        });
        try {
            Chooser.Line line = chooser.choose(ctx, turn(encounter(), Optional.empty(),
                    SpeechActs.SUGGEST_HOME, SpeechActs.END_CHAT));
            assertEquals(SpeechActs.SUGGEST_HOME, line.act());
            assertEquals(Slot.lang("anima.compass.east"), LineSlots.of(line.payload()).orElseThrow().get(0));
        } finally {
            dev.luizloyola.autarkia.core.board.Explore.install(null);
        }
    }

    @Test
    void theSpotTheLeaderNamesIsJudged() {
        java.util.concurrent.atomic.AtomicReference<Optional<String>> verdict =
                new java.util.concurrent.atomic.AtomicReference<>(Optional.of("water"));
        dev.luizloyola.autarkia.core.board.Explore.install(new dev.luizloyola.autarkia.core.direction.HomeLooking() {
            @Override
            public Optional<Look> look(Pos at, dev.luizloyola.anima.core.social.PartyId party,
                    dev.luizloyola.anima.core.brain.knowledge.AgentKnowledge knowledge) {
                return Optional.empty();
            }

            @Override
            public boolean claim(dev.luizloyola.anima.core.social.PartyId party,
                    dev.luizloyola.autarkia.core.direction.HomeJudge.Candidate plot,
                    dev.luizloyola.anima.core.brain.knowledge.AgentKnowledge knowledge) {
                return false;
            }

            @Override
            public Optional<String> judgeHere(AgentId self, Pos at,
                    dev.luizloyola.anima.core.brain.knowledge.AgentKnowledge knowledge) {
                return verdict.get();
            }
        });
        try {
            Utterance here = new Utterance(other, SpeechActs.HERE.key(), Map.of(), 0);
            Chooser.Turn turn = turn(encounter(), Optional.of(here), SpeechActs.NOT_HERE, SpeechActs.HERE_WILL_DO);
            assertEquals(new Chooser.Line(SpeechActs.NOT_HERE, Map.of(Utterance.TOPIC, "water")), chooser.choose(ctx, turn));
            verdict.set(Optional.empty());
            assertEquals(Chooser.Line.of(SpeechActs.HERE_WILL_DO), chooser.choose(ctx, turn));
        } finally {
            dev.luizloyola.autarkia.core.board.Explore.install(null);
        }
    }
}
