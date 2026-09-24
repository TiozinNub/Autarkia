package dev.luizloyola.autarkia.core.person.speech;

import dev.luizloyola.anima.core.agent.need.Gauge;
import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.history.Deed;
import dev.luizloyola.anima.core.brain.history.History;
import dev.luizloyola.anima.core.brain.history.Slot;
import dev.luizloyola.anima.core.brain.sense.Being;
import dev.luizloyola.anima.core.brain.sense.Surroundings;
import dev.luizloyola.anima.core.inv.Inventory;
import dev.luizloyola.anima.core.social.speech.LineSlots;
import dev.luizloyola.anima.core.social.speech.Recounting;
import dev.luizloyola.anima.core.social.speech.Utterance;
import dev.luizloyola.autarkia.core.board.Stock;
import dev.luizloyola.autarkia.core.board.WorkDoings;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.random.RandomGenerator;

/**
 * What a person has to say when nothing else is pressing — {@link PersonActs#SMALL_TALK}'s
 * vocabulary, drawn from what the speaker can tell right now (2026-09-23-small-talk-history-design.md):
 *
 * <ul>
 *   <li><b>the world</b> — the weather and the hour whenever sky light reaches them, and a light
 *       too dim to see by anywhere;</li>
 *   <li><b>the other person</b> — what they visibly hold, sneaking, eating;</li>
 *   <li><b>themselves</b> — a heavy pack, a pile of logs, and any need pressing, so
 *       {@code NEED_COMPANY}'s "I'm alone" shows up as {@code need.company} exactly when the
 *       company gauge has pressure;</li>
 *   <li><b>their day</b> — the last few things they did, told through {@link Recounting};</li>
 *   <li>and work and mood, about nothing in particular.</li>
 * </ul>
 *
 * <p><b>A settler and a player draw from the same well.</b> Everything here reads a
 * {@link Speaker}: a settler's comes off its percepts ({@link #speaker}), a player's off the player
 * (decision: Luiz, 2026-09-24) — so "Fine day for it" is not the player's line at dusk either.
 *
 * <p><b>Nothing is said twice in one conversation</b> (decision: Luiz, 2026-09-23). In the forest a
 * settler told the same meal four times in one chat and said "The work never runs out." three:
 * every draw here leaves out what {@link Said} says this speaker already brought up, and a speaker
 * with nothing new left has nothing to say — the end of the chat for a settler, and no chat button
 * for a player.
 */
public final class Topics {

    /**
     * A body's pool with no world to read, and a player's on an install with nothing to ask —
     * declared HERE and handed to {@link PersonActs#SMALL_TALK} rather than the other way round,
     * because a static initializer reading its own class's act while that act is still being built
     * is a cycle waiting to be written. A speaker that can read the sky tells the weather from it
     * instead: "Fine day for it" in a thunderstorm was the flavour's whole problem.
     */
    static final List<String> FLAVOURS = List.of("weather", "work", "mood");
    private static final List<String> SKY_FLAVOURS = List.of("work", "mood");

    static final String CLEAR_DAY = "world.clear_day";
    static final String CLEAR_NIGHT = "world.clear_night";
    static final String RAIN = "world.rain";
    static final String THUNDER = "world.thunder";
    static final String SNOW = "world.snow";
    static final String DAWN = "world.dawn";
    static final String DUSK = "world.dusk";
    static final String NIGHT = "world.night";
    static final String DARK = "world.dark";
    /** One slot: the item in their hand, named by the game. */
    static final String HOLDING = "them.holding";
    static final String SNEAKING = "them.sneaking";
    static final String EATING = "them.eating";
    static final String HEAVY_PACK = "self.heavy_pack";
    /** One slot: what the pile is of. */
    static final String CARRYING = "self.carrying";

    /**
     * Every topic this class can put on the table beside the needs, with how many slots its lines
     * take — what the lines test walks, so a topic added here without words fails there.
     */
    static final Map<String, Integer> VOCABULARY = vocabulary();

    /**
     * At or under this, the light at a speaker's eyes is too little to see by. An open field at
     * night reads 4, so the sky alone never counts as dark; a cave or a room without a torch does.
     */
    static final int DARK_LIGHT = 3;
    /** Storage slots in use at which a pack is worth complaining about — two thirds of 36. */
    static final int HEAVY_PACK_SLOTS = 24;
    /** A pile worth mentioning — half a stack. */
    static final int PILE = 32;
    /** How many of a speaker's latest deeds are on the table at once. */
    static final int RECENT_DEEDS = 3;

    private Topics() {
    }

    /**
     * What a speaker can talk about, whoever they are.
     *
     * @param around the sky, the hour and the light — empty for a speaker with no world to read
     * @param them the person they are talking to, as far as looking can tell — empty when unseen
     * @param pressingNeeds need keys asking for something, each said as {@code need.<key>}
     * @param deeds what they did lately, newest first — a player has none
     */
    public record Speaker(Optional<Surroundings> around, Optional<Them> them, Pack pack,
            List<String> pressingNeeds, List<History.Entry> deeds, long now) {

        public Speaker {
            pressingNeeds = List.copyOf(pressingNeeds);
            deeds = List.copyOf(deeds);
        }
    }

    /** The other person, as far as anybody looking at them can tell. */
    public record Them(String held, boolean sneaking, boolean eating) {

        public static Them of(Being being) {
            return new Them(being.held(), being.sneaking(),
                    being.activity() == Being.Activity.EATING);
        }
    }

    /** What a speaker carries, as far as small talk asks. */
    public interface Pack {

        /** Storage slots in use — the hotbar and the main pack, never armour or the off hand. */
        int slotsUsed();

        int count(Predicate<String> ids);

        static Pack of(Inventory inventory) {
            return new Pack() {
                @Override
                public int slotsUsed() {
                    int used = 0;
                    for (int slot = 0; slot < Inventory.HOTBAR_SIZE + Inventory.MAIN_SIZE; slot++) {
                        if (!inventory.get(slot).isEmpty()) {
                            used++;
                        }
                    }
                    return used;
                }

                @Override
                public int count(Predicate<String> ids) {
                    return inventory.count(ids);
                }
            };
        }
    }

    /** One thing to bring up: a topic key and whatever it names. */
    public record Topic(String key, List<Slot> slots) {

        public Topic {
            slots = List.copyOf(slots);
        }

        static Topic of(String key) {
            return new Topic(key, List.of());
        }

        Map<String, String> payload() {
            return LineSlots.with(Map.of(Utterance.TOPIC, key), slots);
        }
    }

    /** What one speaker has already brought up in one conversation: topics by key, deeds by deed. */
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

    // ── a settler ────────────────────────────────────────────────────────────────────────────

    /** A settler as its percepts tell it. */
    public static Speaker speaker(BrainContext ctx, Optional<Being> them) {
        List<String> pressing = new ArrayList<>();
        for (Gauge gauge : ctx.percepts().needs().all()) {
            if (gauge.pressure() > 0.0) {
                pressing.add(gauge.kind().key());
            }
        }
        return new Speaker(ctx.percepts().surroundings(), them.map(Them::of),
                Pack.of(ctx.percepts().inventory()), pressing, ctx.history(),
                ctx.percepts().time());
    }

    public static Optional<Map<String, String>> pick(BrainContext ctx, Optional<Being> them,
            Said said) {
        return pick(speaker(ctx, them), said, ctx.random());
    }

    public static Optional<Map<String, String>> latestDeed(BrainContext ctx, Said said) {
        return latestDeed(speaker(ctx, Optional.empty()), said);
    }

    public static boolean anythingLeft(BrainContext ctx, Optional<Being> them, Said said) {
        return anythingLeft(speaker(ctx, them), said);
    }

    static List<Topic> options(BrainContext ctx, Optional<Being> them) {
        return options(speaker(ctx, them));
    }

    /** The keys of {@link #options} — what a readout or a test compares. */
    static List<String> keys(BrainContext ctx, Optional<Being> them) {
        return options(ctx, them).stream().map(Topic::key).toList();
    }

    static List<History.Entry> recentDeeds(BrainContext ctx) {
        return recentDeeds(speaker(ctx, Optional.empty()));
    }

    // ── anybody ──────────────────────────────────────────────────────────────────────────────

    /**
     * A SMALL_TALK payload about something not yet {@code said}: a topic with whatever it names,
     * or a told deed. Drawn uniformly over all of them. Empty once nothing new is left.
     */
    public static Optional<Map<String, String>> pick(Speaker speaker, Said said,
            RandomGenerator random) {
        List<Topic> topics = freshTopics(speaker, said);
        List<History.Entry> deeds = freshDeeds(speaker, said);
        if (topics.isEmpty() && deeds.isEmpty()) {
            return Optional.empty();
        }
        int draw = random.nextInt(topics.size() + deeds.size());
        return Optional.of(draw < topics.size() ? topics.get(draw).payload()
                : Recounting.payload(deeds.get(draw - topics.size()), speaker.now()));
    }

    /**
     * The speaker's latest deed not yet {@code said}, told — how one answers somebody else's;
     * empty with none left to tell.
     */
    public static Optional<Map<String, String>> latestDeed(Speaker speaker, Said said) {
        return freshDeeds(speaker, said).stream().findFirst()
                .map(entry -> Recounting.payload(entry, speaker.now()));
    }

    /** Whether anything is left to bring up that is not already {@code said}. */
    public static boolean anythingLeft(Speaker speaker, Said said) {
        return !freshTopics(speaker, said).isEmpty() || !freshDeeds(speaker, said).isEmpty();
    }

    /** Everything on the table right now, deeds aside. */
    static List<Topic> options(Speaker speaker) {
        List<Topic> out = new ArrayList<>();
        for (String flavour : speaker.around().isPresent() ? SKY_FLAVOURS : FLAVOURS) {
            out.add(Topic.of(flavour));
        }
        speaker.around().ifPresent(around -> world(around, out));
        speaker.them().ifPresent(them -> them(them, out));
        self(speaker.pack(), out);
        for (String need : speaker.pressingNeeds()) {
            out.add(Topic.of("need." + need));
        }
        return out;
    }

    /**
     * The needs a body with no gauges shows — a player: hungry below 15 food of 20 (natural
     * healing stops at 18, sprinting at 6, and this sits between the two), worn down at half
     * health, out of breath the moment the air runs short. Keys of the gauges a settler has, so
     * both say the same line.
     */
    public static List<String> needsShown(int food, float health, float maxHealth, int air,
            int maxAir) {
        List<String> out = new ArrayList<>();
        if (food < 15) {
            out.add("hunger");
        }
        if (health <= maxHealth / 2) {
            out.add("vigor");
        }
        if (air < maxAir) {
            out.add("breath");
        }
        return out;
    }

    static List<History.Entry> recentDeeds(Speaker speaker) {
        List<History.Entry> all = speaker.deeds();
        return all.subList(0, Math.min(RECENT_DEEDS, all.size()));
    }

    /**
     * The weather and the hour whenever sky light reaches the speaker — under a canopy as much as in
     * the open, where rain and dusk still show. A cave or a sealed room tells neither.
     */
    private static void world(Surroundings around, List<Topic> out) {
        if (around.outdoors()) {
            switch (around.weather()) {
                case CLEAR -> {
                    if (around.phase() == Surroundings.DayPhase.NIGHT) {
                        out.add(Topic.of(CLEAR_NIGHT));
                    } else if (around.phase() != Surroundings.DayPhase.DUSK) {
                        out.add(Topic.of(CLEAR_DAY));
                    }
                }
                case RAIN -> out.add(Topic.of(RAIN));
                case THUNDER -> out.add(Topic.of(THUNDER));
                case SNOW -> out.add(Topic.of(SNOW));
            }
            switch (around.phase()) {
                case DAWN -> out.add(Topic.of(DAWN));
                case DUSK -> out.add(Topic.of(DUSK));
                case NIGHT -> out.add(Topic.of(NIGHT));
                case DAY -> {
                }
            }
        }
        if (around.light() <= DARK_LIGHT) {
            out.add(Topic.of(DARK));
        }
    }

    private static void them(Them them, List<Topic> out) {
        if (!them.held().isEmpty()) {
            out.add(new Topic(HOLDING, List.of(Slot.item(them.held()))));
        }
        if (them.sneaking()) {
            out.add(Topic.of(SNEAKING));
        }
        if (them.eating()) {
            out.add(Topic.of(EATING));
        }
    }

    private static void self(Pack pack, List<Topic> out) {
        if (pack.slotsUsed() >= HEAVY_PACK_SLOTS) {
            out.add(Topic.of(HEAVY_PACK));
        }
        if (pack.count(Stock.LOGS.matcher()) >= PILE) {
            out.add(new Topic(CARRYING, List.of(WorkDoings.goods(Stock.LOGS))));
        }
    }

    private static List<Topic> freshTopics(Speaker speaker, Said said) {
        List<Topic> fresh = new ArrayList<>(options(speaker));
        fresh.removeIf(topic -> said.topics().contains(topic.key()));
        return fresh;
    }

    /** The latest {@link #RECENT_DEEDS}, less those told — an older one never moves up to replace them. */
    private static List<History.Entry> freshDeeds(Speaker speaker, Said said) {
        List<History.Entry> fresh = new ArrayList<>(recentDeeds(speaker));
        fresh.removeIf(entry -> said.deeds().contains(entry.deed()));
        return fresh;
    }

    private static Map<String, Integer> vocabulary() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String flavour : FLAVOURS) {
            out.put(flavour, 0);
        }
        for (String key : List.of(CLEAR_DAY, CLEAR_NIGHT, RAIN, THUNDER, SNOW, DAWN, DUSK, NIGHT,
                DARK, SNEAKING, EATING, HEAVY_PACK)) {
            out.put(key, 0);
        }
        out.put(HOLDING, 1);
        out.put(CARRYING, 1);
        return Map.copyOf(out);
    }
}
