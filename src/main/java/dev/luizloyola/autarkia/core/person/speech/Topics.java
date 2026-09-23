package dev.luizloyola.autarkia.core.person.speech;

import dev.luizloyola.anima.core.agent.need.Gauge;
import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.history.Deed;
import dev.luizloyola.anima.core.brain.history.History;
import dev.luizloyola.anima.core.social.speech.Recounting;
import dev.luizloyola.anima.core.social.speech.Utterance;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * What a person has to say when nothing else is pressing — {@link PersonActs#SMALL_TALK}'s
 * vocabulary. Weather, work and mood are always on the table; a need presses its own topic onto
 * it the moment it starts asking for something, so {@code NEED_COMPANY}'s "I'm alone" shows up
 * here as {@code need.company} exactly when the company gauge has pressure; and the last few
 * things the body did are on it too, told through {@link Recounting}.
 *
 * <p><b>Nothing is said twice in one conversation</b> (decision: Luiz, 2026-09-23). In the forest a
 * settler told the same meal four times in one chat and said "The work never runs out." three:
 * every draw here leaves out what {@link Said} says this body already brought up, and a body with
 * nothing new left has nothing to say — which {@code PersonChooser} reads as the end of it.
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

    /** What one body has already brought up in one conversation: topics by key, deeds by deed. */
    public record Said(Set<String> topics, Set<Deed> deeds) {

        public static final Said NOTHING = new Said(Set.of(), Set.of());

        public Said {
            topics = Set.copyOf(topics);
            deeds = Set.copyOf(deeds);
        }

        /** What the small talk among {@code lines} that {@code own} accepts brought up. */
        public static Said in(List<Utterance> lines, Predicate<Utterance> own) {
            Set<String> topics = new HashSet<>();
            Set<Deed> deeds = new HashSet<>();
            for (Utterance line : lines) {
                if (!own.test(line) || !line.act().equals(PersonActs.SMALL_TALK.key())) {
                    continue;
                }
                Optional<Recounting.Told> told = Recounting.read(line.payload());
                if (told.isPresent()) {
                    deeds.add(told.get().deed());
                } else if (line.payload().containsKey(Utterance.TOPIC)) {
                    topics.add(line.payload().get(Utterance.TOPIC));
                }
            }
            return new Said(topics, deeds);
        }
    }

    /**
     * A SMALL_TALK payload about something not yet {@code said}: {@code {"topic": key}} for a
     * flavour or a need, or a told deed. Drawn uniformly over all of them, so with a full history
     * about half of what a settler volunteers is about its own day. Empty once nothing new is left.
     */
    public static Optional<Map<String, String>> pick(BrainContext ctx, Said said) {
        List<String> topics = freshTopics(ctx, said);
        List<History.Entry> deeds = freshDeeds(ctx, said);
        if (topics.isEmpty() && deeds.isEmpty()) {
            return Optional.empty();
        }
        int draw = ctx.random().nextInt(topics.size() + deeds.size());
        return Optional.of(draw < topics.size() ? Map.of(Utterance.TOPIC, topics.get(draw))
                : Recounting.payload(deeds.get(draw - topics.size()), ctx.percepts().time()));
    }

    /**
     * This body's latest deed not yet {@code said}, told — how one answers somebody else's; empty
     * with none left to tell.
     */
    public static Optional<Map<String, String>> latestDeed(BrainContext ctx, Said said) {
        return freshDeeds(ctx, said).stream().findFirst()
                .map(entry -> Recounting.payload(entry, ctx.percepts().time()));
    }

    /** Whether anything is left to bring up that is not already {@code said}. */
    public static boolean anythingLeft(BrainContext ctx, Said said) {
        return !freshTopics(ctx, said).isEmpty() || !freshDeeds(ctx, said).isEmpty();
    }

    static List<History.Entry> recentDeeds(BrainContext ctx) {
        List<History.Entry> all = ctx.history();
        return all.subList(0, Math.min(RECENT_DEEDS, all.size()));
    }

    private static List<String> freshTopics(BrainContext ctx, Said said) {
        List<String> fresh = new ArrayList<>(options(ctx));
        fresh.removeAll(said.topics());
        return fresh;
    }

    /** The latest {@link #RECENT_DEEDS}, less those told — an older one never moves up to replace them. */
    private static List<History.Entry> freshDeeds(BrainContext ctx, Said said) {
        List<History.Entry> fresh = new ArrayList<>(recentDeeds(ctx));
        fresh.removeIf(entry -> said.deeds().contains(entry.deed()));
        return fresh;
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
