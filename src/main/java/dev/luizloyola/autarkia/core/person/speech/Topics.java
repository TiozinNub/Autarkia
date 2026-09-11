package dev.luizloyola.autarkia.core.person.speech;

import dev.luizloyola.anima.core.agent.need.Gauge;
import dev.luizloyola.anima.core.brain.BrainContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What a person has to say when nothing else is pressing — {@link PersonActs#SMALL_TALK}'s
 * vocabulary. Weather, work and mood are always on the table; a need presses its own topic onto
 * it the moment it starts asking for something, so {@code NEED_COMPANY}'s "I'm alone" shows up
 * here as {@code need.company} exactly when the company gauge has pressure.
 */
public final class Topics {

    /**
     * The three that are always on the table — declared HERE and handed to
     * {@link PersonActs#SMALL_TALK} rather than the other way round, because a static initializer
     * reading its own class's act while that act is still being built is a cycle waiting to be
     * written. One list, two readers.
     */
    static final List<String> FLAVOURS = List.of("weather", "work", "mood");

    private Topics() {
    }

    /** payload {"topic": key} for SMALL_TALK; flavour first, then whatever the body feels. */
    public static Map<String, String> pick(BrainContext ctx) {
        List<String> options = options(ctx);
        String topic = options.get(ctx.random().nextInt(options.size()));
        return Map.of("topic", topic);
    }

    /** Always the three flavours, plus {@code "need." + kind.key()} for every gauge pressing. */
    static List<String> options(BrainContext ctx) {
        List<String> options = new ArrayList<>(FLAVOURS);
        for (Gauge gauge : ctx.percepts().needs().all()) {
            if (gauge.pressure() > 0.0) {
                options.add("need." + gauge.kind().key());
            }
        }
        return options;
    }
}
