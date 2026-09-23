package dev.luizloyola.autarkia.core.person.speech;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.social.speech.Utterance;
import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.social.speech.Recounting;
import dev.luizloyola.anima.core.brain.history.Slot;
import dev.luizloyola.anima.core.brain.history.History;
import dev.luizloyola.anima.core.brain.history.Doings;
import dev.luizloyola.anima.core.brain.history.Deed;
import dev.luizloyola.anima.core.brain.task.FakeContext;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link Topics#options} is pure and tested directly; {@link Topics#pick} only draws from it. */
class TopicsTest {

    @Test
    @DisplayName("the three flavours are always on the table")
    void alwaysTheFlavours() {
        FakeContext ctx = new FakeContext();
        ctx.percepts.company.setValue(0.6); // comfortable — no gauge is pressing
        List<String> options = Topics.options(ctx);
        assertTrue(options.containsAll(List.of("weather", "work", "mood")));
    }

    @Test
    @DisplayName("need.company joins the options exactly when the company gauge presses")
    void needCompanyWhenItPresses() {
        FakeContext ctx = new FakeContext();

        ctx.percepts.company.setValue(0.6); // content: pressure 0.0
        assertFalse(Topics.options(ctx).contains("need.company"),
                "a body with company enough has nothing to say about it");

        ctx.percepts.company.setValue(0.0); // desolate: pressure > 0.0
        assertTrue(Topics.options(ctx).contains("need.company"),
                "a lonely body brings it up");
    }

    @Test
    @DisplayName("pick draws one of the options uniformly via ctx.random()")
    void pickDrawsFromOptions() {
        FakeContext ctx = new FakeContext();
        ctx.percepts.company.setValue(0.6);

        Map<String, String> line = Topics.pick(ctx, Topics.Said.NOTHING).orElseThrow();

        assertEquals(1, line.size());
        assertTrue(Topics.options(ctx).contains(line.get("topic")));
    }

    private static History.Entry fled(String what, long at) {
        return new History.Entry(Deed.of(Doings.FLEEING, Slot.entity(what)), at, 1);
    }

    @Test
    @DisplayName("the latest three deeds join the draw, told with how long ago")
    void recentDeedsAreOnTheTable() {
        FakeContext ctx = new FakeContext();
        ctx.percepts.company.setValue(0.6);
        for (String what : List.of("zombie", "spider", "creeper", "witch", "slime")) {
            ctx.history.add(fled(what, 0)); // newest first, as ctx.history() hands it over
        }

        Set<String> told = new HashSet<>();
        int deeds = 0;
        for (int i = 0; i < 300; i++) {
            Map<String, String> line = Topics.pick(ctx, Topics.Said.NOTHING).orElseThrow();
            if (line.containsKey(Recounting.DID)) {
                deeds++;
                told.add(Recounting.read(line).orElseThrow().deed().slots().get(0).value());
            } else {
                assertTrue(Topics.options(ctx).contains(line.get("topic")));
            }
        }
        assertTrue(deeds > 0, "a body with a day behind it talks about it");
        assertEquals(Set.of("zombie", "spider", "creeper"), told, "the latest three, never older");
    }

    @Test
    @DisplayName("an answer to a deed is this body's latest, or nothing without one")
    void theLatestDeedAnswers() {
        FakeContext ctx = new FakeContext();
        assertTrue(Topics.latestDeed(ctx, Topics.Said.NOTHING).isEmpty());

        ctx.history.add(fled("zombie", 0));
        ctx.history.add(fled("spider", 0));
        assertEquals("zombie", Recounting.read(Topics.latestDeed(ctx, Topics.Said.NOTHING).orElseThrow()).orElseThrow()
                .deed().slots().get(0).value());
    }

    @Test
    @DisplayName("nothing already said is drawn again, and with everything said there is nothing to say")
    void nothingSaidTwice() {
        FakeContext ctx = new FakeContext();
        ctx.percepts.company.setValue(0.6);
        ctx.history.add(fled("zombie", 0));
        List<String> topics = Topics.options(ctx);

        Topics.Said allButMood = new Topics.Said(
                new HashSet<>(topics.stream().filter(t -> !t.equals("mood")).toList()),
                Set.of(Deed.of(Doings.FLEEING, Slot.entity("zombie"))));
        for (int i = 0; i < 50; i++) {
            assertEquals(Map.of("topic", "mood"), Topics.pick(ctx, allButMood).orElseThrow());
        }

        Topics.Said everything = new Topics.Said(new HashSet<>(topics), allButMood.deeds());
        assertFalse(Topics.anythingLeft(ctx, everything));
        assertTrue(Topics.pick(ctx, everything).isEmpty(), "nothing new, nothing said");
    }

    @Test
    @DisplayName("the latest deed not yet told answers — an older one never moves up to replace it")
    void theLatestUntoldDeedAnswers() {
        FakeContext ctx = new FakeContext();
        for (String what : List.of("zombie", "spider", "creeper", "witch")) {
            ctx.history.add(fled(what, 0));
        }
        Topics.Said toldZombie = new Topics.Said(Set.of(),
                Set.of(Deed.of(Doings.FLEEING, Slot.entity("zombie"))));
        assertEquals("spider", Recounting.read(Topics.latestDeed(ctx, toldZombie).orElseThrow())
                .orElseThrow().deed().slots().get(0).value());

        Topics.Said toldTheLatestThree = new Topics.Said(Set.of(), Set.of(
                Deed.of(Doings.FLEEING, Slot.entity("zombie")),
                Deed.of(Doings.FLEEING, Slot.entity("spider")),
                Deed.of(Doings.FLEEING, Slot.entity("creeper"))));
        assertTrue(Topics.latestDeed(ctx, toldTheLatestThree).isEmpty(),
                "the witch is fourth — past what is on the table");
    }

    @Test
    @DisplayName("Said reads the speaker's own small talk: topics by key, deeds by deed")
    void saidReadsOwnSmallTalk() {
        AgentId me = AgentId.random();
        AgentId them = AgentId.random();
        String smallTalk = PersonActs.SMALL_TALK.key();
        List<Utterance> lines = List.of(
                new Utterance(me, smallTalk, Map.of("topic", "weather"), 1),
                new Utterance(them, smallTalk, Map.of("topic", "work"), 2),
                new Utterance(me, smallTalk, Recounting.payload(fled("zombie", 0), 0), 3),
                new Utterance(me, "greeting", Map.of(), 4));

        Topics.Said said = Topics.Said.in(lines, line -> me.equals(line.author()));

        assertEquals(Set.of("weather"), said.topics(), "theirs are theirs to repeat");
        assertEquals(Set.of(Deed.of(Doings.FLEEING, Slot.entity("zombie"))), said.deeds());
    }
}
