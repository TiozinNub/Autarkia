package dev.luizloyola.autarkia.core.person.speech;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.task.FakeContext;
import java.util.List;
import java.util.Map;
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
}
