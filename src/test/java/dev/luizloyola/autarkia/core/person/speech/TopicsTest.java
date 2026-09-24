package dev.luizloyola.autarkia.core.person.speech;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.inv.Inventory;
import dev.luizloyola.anima.core.social.speech.LineSlots;
import dev.luizloyola.anima.core.inv.ItemStack;
import dev.luizloyola.anima.core.agent.need.NeedKind;
import dev.luizloyola.anima.core.brain.history.DoingLines;
import dev.luizloyola.anima.core.brain.task.FakePercepts;
import dev.luizloyola.anima.core.brain.sense.Surroundings;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.sense.BeingId;
import dev.luizloyola.anima.core.brain.sense.Being;
import dev.luizloyola.anima.core.social.speech.Utterance;
import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.social.speech.Recounting;
import dev.luizloyola.anima.core.brain.history.Slot;
import dev.luizloyola.anima.core.brain.history.History;
import dev.luizloyola.anima.core.brain.history.Doings;
import dev.luizloyola.anima.core.brain.history.Deed;
import dev.luizloyola.anima.core.brain.task.FakeContext;
import java.util.random.RandomGenerator;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.Optional;
import java.util.ArrayList;
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
        List<String> options = Topics.keys(ctx, Optional.empty());
        assertTrue(options.containsAll(List.of("weather", "work", "mood")));
    }

    @Test
    @DisplayName("need.company joins the options exactly when the company gauge presses")
    void needCompanyWhenItPresses() {
        FakeContext ctx = new FakeContext();

        ctx.percepts.company.setValue(0.6); // content: pressure 0.0
        assertFalse(Topics.keys(ctx, Optional.empty()).contains("need.company"),
                "a body with company enough has nothing to say about it");

        ctx.percepts.company.setValue(0.0); // desolate: pressure > 0.0
        assertTrue(Topics.keys(ctx, Optional.empty()).contains("need.company"),
                "a lonely body brings it up");
    }

    @Test
    @DisplayName("pick draws one of the options uniformly via ctx.random()")
    void pickDrawsFromOptions() {
        FakeContext ctx = new FakeContext();
        ctx.percepts.company.setValue(0.6);

        Map<String, String> line = Topics.pick(ctx, Optional.empty(), Topics.Said.NOTHING).orElseThrow();

        assertEquals(1, line.size());
        assertTrue(Topics.keys(ctx, Optional.empty()).contains(line.get("topic")));
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
            Map<String, String> line = Topics.pick(ctx, Optional.empty(), Topics.Said.NOTHING).orElseThrow();
            if (line.containsKey(Recounting.DID)) {
                deeds++;
                told.add(Recounting.read(line).orElseThrow().deed().slots().get(0).value());
            } else {
                assertTrue(Topics.keys(ctx, Optional.empty()).contains(line.get("topic")));
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
        List<String> topics = Topics.keys(ctx, Optional.empty());

        Topics.Said allButMood = new Topics.Said(
                new HashSet<>(topics.stream().filter(t -> !t.equals("mood")).toList()),
                Set.of(Deed.of(Doings.FLEEING, Slot.entity("zombie"))));
        for (int i = 0; i < 50; i++) {
            assertEquals(Map.of("topic", "mood"), Topics.pick(ctx, Optional.empty(), allButMood).orElseThrow());
        }

        Topics.Said everything = new Topics.Said(new HashSet<>(topics), allButMood.deeds());
        assertFalse(Topics.anythingLeft(ctx, Optional.empty(), everything));
        assertTrue(Topics.pick(ctx, Optional.empty(), everything).isEmpty(), "nothing new, nothing said");
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

    // ── the sources ──────────────────────────────────────────────────────────────────────────

    private static List<String> keysUnder(Surroundings around) {
        FakeContext ctx = new FakeContext();
        ctx.percepts.company.setValue(0.6);
        ctx.percepts.surroundings = around;
        return Topics.keys(ctx, Optional.empty());
    }

    @Test
    @DisplayName("the sky tells the weather and the hour; the fixed weather line goes")
    void theWorldIsReadOffTheSky() {
        List<String> day = keysUnder(new Surroundings(Surroundings.Weather.CLEAR,
                Surroundings.DayPhase.DAY, 15, true));
        assertTrue(day.contains(Topics.CLEAR_DAY), day.toString());
        assertFalse(day.contains("weather"), "a settler who can see the sky does not guess at it");
        assertFalse(day.contains(Topics.NIGHT));

        List<String> night = keysUnder(new Surroundings(Surroundings.Weather.CLEAR,
                Surroundings.DayPhase.NIGHT, 4, true));
        assertTrue(night.containsAll(List.of(Topics.CLEAR_NIGHT, Topics.NIGHT)), night.toString());
        assertFalse(night.contains(Topics.CLEAR_DAY), "no \"fine day\" at midnight");
        assertFalse(night.contains(Topics.DARK), "an open field at night is not dark enough");

        List<String> storm = keysUnder(new Surroundings(Surroundings.Weather.THUNDER,
                Surroundings.DayPhase.DUSK, 9, true));
        assertTrue(storm.containsAll(List.of(Topics.RAIN, Topics.DUSK)), storm.toString());
        assertFalse(storm.contains(Topics.THUNDER), "a storm with no clap heard is only rain");
    }

    @Test
    @DisplayName("under a roof the sky tells nothing, but the dark still does")
    void underARoofOnlyTheDarkIsSaid() {
        List<String> cave = keysUnder(new Surroundings(Surroundings.Weather.RAIN,
                Surroundings.DayPhase.NIGHT, 0, false));
        assertFalse(cave.contains(Topics.RAIN), cave.toString());
        assertFalse(cave.contains(Topics.NIGHT), cave.toString());
        assertTrue(cave.contains(Topics.DARK), cave.toString());
    }

    @Test
    @DisplayName("what they visibly hold, and eating, are theirs to be asked about")
    void theOtherPersonIsReadOffWhatCanBeSeen() {
        FakeContext ctx = new FakeContext();
        Being smith = FakePercepts.personHolding(BeingId.of(AgentId.random()), new Pos(2, 64, 0), 2.0,
                "minecraft:iron_axe");

        assertTrue(Topics.options(ctx, Optional.of(smith)).contains(
                new Topics.Topic(Topics.HOLDING, List.of(Slot.item("minecraft:iron_axe")))));
        assertFalse(Topics.keys(ctx, Optional.empty()).contains(Topics.HOLDING),
                "nobody seen, nothing in hand to remark on");

        Being eating = FakePercepts.personDoing(new Pos(2, 64, 0), 2.0, Being.Activity.EATING,
                Being.Locomotion.STILL);
        assertTrue(Topics.keys(ctx, Optional.of(eating)).contains(Topics.EATING));
    }

    @Test
    @DisplayName("a heavy pack and a pile of logs are its own to mention")
    void itselfIsReadOffItsPack() {
        FakeContext ctx = new FakeContext();
        assertFalse(Topics.keys(ctx, Optional.empty()).contains(Topics.HEAVY_PACK));

        for (int slot = 0; slot < Topics.HEAVY_PACK_SLOTS - 1; slot++) {
            ctx.percepts.inventory.set(slot, ItemStack.of("minecraft:dirt", 1, 64));
        }
        ctx.percepts.inventory.set(Topics.HEAVY_PACK_SLOTS - 1,
                ItemStack.of("minecraft:oak_log", Topics.PILE, 64));

        List<Topics.Topic> options = Topics.options(ctx, Optional.empty());
        assertTrue(options.contains(new Topics.Topic(Topics.HEAVY_PACK, List.of())));
        assertTrue(options.contains(new Topics.Topic(Topics.CARRYING,
                List.of(Slot.lang("autarkia.goods.logs")))));
    }

    @Test
    @DisplayName("a topic that names something carries it in the line it becomes")
    void aNamingTopicCarriesItsSlot() {
        FakeContext ctx = new FakeContext();
        ctx.percepts.surroundings = new Surroundings(Surroundings.Weather.CLEAR,
                Surroundings.DayPhase.DAY, 15, false);
        Being smith = FakePercepts.personHolding(BeingId.of(AgentId.random()), new Pos(2, 64, 0), 2.0,
                "minecraft:iron_axe");
        Set<String> everythingElse = new HashSet<>(Topics.keys(ctx, Optional.of(smith)));
        everythingElse.remove(Topics.HOLDING);

        Map<String, String> line = Topics.pick(ctx, Optional.of(smith),
                new Topics.Said(everythingElse, Set.of())).orElseThrow();

        assertEquals(Topics.HOLDING, line.get("topic"));
        assertEquals(Optional.of(List.of(Slot.item("minecraft:iron_axe"))), LineSlots.of(line));
    }

    // ── the words ────────────────────────────────────────────────────────────────────────────

    private static final Pattern ARG = Pattern.compile("%(?:(\\d+)\\$)?([a-zA-Z%])");

    @Test
    @DisplayName("every topic this can say, and every need, has both its lines — arguments in range")
    void everyTopicHasItsLines() {
        Map<String, String> en = DoingLines.load(TopicsTest.class, "/assets/autarkia/lang/en_us.json");
        Map<String, Integer> topics = new java.util.LinkedHashMap<>(Topics.VOCABULARY);
        for (NeedKind need : NeedKind.all()) {
            topics.put("need." + need.key(), 0);
        }
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, Integer> topic : topics.entrySet()) {
            for (int variant = 1; variant <= PersonActs.SMALL_TALK.variants(); variant++) {
                String key = "autarkia.speech.small_talk." + topic.getKey() + "." + variant;
                String line = en.get(key);
                if (line == null) {
                    problems.add(key + " is missing");
                    continue;
                }
                Matcher m = ARG.matcher(line);
                while (m.find()) {
                    int position = m.group(1) == null ? -1 : Integer.parseInt(m.group(1));
                    if (!m.group().equals("%%") && (position < 1 || position > topic.getValue())) {
                        problems.add(key + " uses " + m.group() + " but carries "
                                + topic.getValue() + " slot(s)");
                    }
                }
            }
        }
        assertEquals(List.of(), problems);
    }

    // ── a player, or anybody with no brain ───────────────────────────────────────────────────

    @Test
    @DisplayName("a body with no gauges shows hunger below 15 food, fatigue at half health, breath when short")
    void theNeedsABodyShows() {
        assertEquals(List.of(), Topics.needsShown(20, 20f, 20f, 300, 300));
        assertEquals(List.of("hunger"), Topics.needsShown(14, 20f, 20f, 300, 300));
        assertEquals(List.of("vigor"), Topics.needsShown(15, 10f, 20f, 300, 300));
        assertEquals(List.of("breath"), Topics.needsShown(20, 20f, 20f, 299, 300));
    }

    /** A player at dusk under a canopy, facing a settler with an axe, forty logs in the pack, peckish. */
    private static Topics.Speaker playerAtDusk() {
        Inventory pack = new Inventory();
        pack.set(0, ItemStack.of("minecraft:oak_log", 40, 64));
        return new Topics.Speaker(
                Optional.of(new Surroundings(Surroundings.Weather.CLEAR,
                        Surroundings.DayPhase.DUSK, 9, true)),
                Optional.of(new Topics.Them("minecraft:iron_axe", false, false)),
                Topics.Pack.of(pack), List.of("hunger"), List.of(), 0);
    }

    @Test
    @DisplayName("a player talks about the sky over them, the settler in front of them, and themselves")
    void aPlayerDrawsFromTheSameWell() {
        List<String> keys = Topics.options(playerAtDusk()).stream().map(Topics.Topic::key).toList();

        assertTrue(keys.containsAll(List.of(Topics.DUSK, Topics.HOLDING, Topics.CARRYING,
                "need.hunger")), keys.toString());
        assertFalse(keys.contains("weather"), "no \"Fine day for it\" at dusk, for a player either");
    }

    @Test
    @DisplayName("a player never says the same thing twice, and runs out like anybody")
    void aPlayerNeverRepeatsEither() {
        Topics.Speaker player = playerAtDusk();
        Set<String> allButDusk = new HashSet<>(
                Topics.options(player).stream().map(Topics.Topic::key).toList());
        allButDusk.remove(Topics.DUSK);

        for (int i = 0; i < 30; i++) {
            assertEquals(Topics.DUSK, Topics.pick(player, new Topics.Said(allButDusk, Set.of()),
                    RandomGenerator.getDefault()).orElseThrow().get("topic"));
        }
        allButDusk.add(Topics.DUSK);
        assertFalse(Topics.anythingLeft(player, new Topics.Said(allButDusk, Set.of())),
                "everything said: the chat button goes, the goodbye stays");
    }

    @Test
    @DisplayName("under a canopy the rain and the hour still show")
    void aCanopyStillShowsTheSky() {
        List<String> forest = keysUnder(new Surroundings(Surroundings.Weather.RAIN,
                Surroundings.DayPhase.NIGHT, 3, true));
        assertTrue(forest.containsAll(List.of(Topics.RAIN, Topics.NIGHT)), forest.toString());
    }

    // ── thunder, heard ───────────────────────────────────────────────────────────────────────

    private static Topics.Speaker hearing(Surroundings.Thunderclap clap, boolean outdoors) {
        return new Topics.Speaker(Optional.of(new Surroundings(Surroundings.Weather.THUNDER,
                        Surroundings.DayPhase.DAY, 12, outdoors, Optional.of(clap))),
                Optional.empty(), Topics.Pack.of(new Inventory()), List.of("hunger"), List.of(), 0);
    }

    @Test
    @DisplayName("a clap just heard is said first — a body that heard thunder remarks on it")
    void aFreshClapIsSaidFirst() {
        for (int i = 0; i < 30; i++) {
            assertEquals(Topics.THUNDER, Topics.pick(hearing(new Surroundings.Thunderclap(40, 90.0),
                    true), Topics.Said.NOTHING, RandomGenerator.getDefault()).orElseThrow().get("topic"));
        }
        assertEquals(Topics.THUNDER_CLOSE, Topics.pick(hearing(new Surroundings.Thunderclap(40, 12.0),
                true), Topics.Said.NOTHING, RandomGenerator.getDefault()).orElseThrow().get("topic"),
                "near enough, it struck close");
    }

    @Test
    @DisplayName("thunder is heard under a roof, forgotten after a while, and said only once")
    void thunderIsASound() {
        List<String> indoors = Topics.options(hearing(new Surroundings.Thunderclap(40, 90.0), false))
                .stream().map(Topics.Topic::key).toList();
        assertTrue(indoors.contains(Topics.THUNDER), indoors.toString());
        assertFalse(indoors.contains(Topics.RAIN), "the rain is the sky's to tell, and there is none");

        List<String> stale = Topics.options(hearing(new Surroundings.Thunderclap(
                Topics.THUNDER_FRESH_TICKS + 1, 90.0), true)).stream().map(Topics.Topic::key).toList();
        assertFalse(stale.contains(Topics.THUNDER), stale.toString());

        String next = Topics.pick(hearing(new Surroundings.Thunderclap(40, 90.0), true),
                new Topics.Said(Set.of(Topics.THUNDER), Set.of()), RandomGenerator.getDefault())
                .orElseThrow().get("topic");
        assertFalse(Topics.THUNDER.equals(next), "remarked on once, and the chat moves on");
    }
}
