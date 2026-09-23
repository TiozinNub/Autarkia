package dev.luizloyola.autarkia.core.person.speech;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

        Map<String, String> line = Topics.pick(ctx);

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
            Map<String, String> line = Topics.pick(ctx);
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
        assertTrue(Topics.latestDeed(ctx).isEmpty());

        ctx.history.add(fled("zombie", 0));
        ctx.history.add(fled("spider", 0));
        assertEquals("zombie", Recounting.read(Topics.latestDeed(ctx).orElseThrow()).orElseThrow()
                .deed().slots().get(0).value());
    }
}
