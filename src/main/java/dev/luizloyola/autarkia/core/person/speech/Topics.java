package dev.luizloyola.autarkia.core.person.speech;

import dev.luizloyola.anima.core.agent.need.Gauge;
import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.history.History;
import dev.luizloyola.anima.core.social.speech.Recounting;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What a person has to say when nothing else is pressing — {@link PersonActs#SMALL_TALK}'s
 * vocabulary. Weather, work and mood are always on the table; a need presses its own topic onto
 * it the moment it starts asking for something, so {@code NEED_COMPANY}'s "I'm alone" shows up
 * here as {@code need.company} exactly when the company gauge has pressure; and the last few
 * things the body did are on it too, told through {@link Recounting}.
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

    /** How many of a body's latest deeds are on the table at once. */
    static final int RECENT_DEEDS = 3;

    /**
     * A SMALL_TALK payload: {@code {"topic": key}} for a flavour or a need, or a told deed. Drawn
     * uniformly over all of them, so with a full history about half of what a settler volunteers
     * is about its own day.
     */
    public static Map<String, String> pick(BrainContext ctx) {
        List<String> options = options(ctx);
        List<History.Entry> deeds = recentDeeds(ctx);
        int draw = ctx.random().nextInt(options.size() + deeds.size());
        return draw < options.size() ? Map.of("topic", options.get(draw))
                : Recounting.payload(deeds.get(draw - options.size()), ctx.percepts().time());
    }

    /** This body's latest deed, told — how one answers somebody else's; empty with none to tell. */
    public static Optional<Map<String, String>> latestDeed(BrainContext ctx) {
        List<History.Entry> deeds = ctx.history();
        return deeds.isEmpty() ? Optional.empty()
                : Optional.of(Recounting.payload(deeds.get(0), ctx.percepts().time()));
    }

    static List<History.Entry> recentDeeds(BrainContext ctx) {
        List<History.Entry> all = ctx.history();
        return all.subList(0, Math.min(RECENT_DEEDS, all.size()));
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
