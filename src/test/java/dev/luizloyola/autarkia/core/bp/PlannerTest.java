package dev.luizloyola.autarkia.core.bp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.luizloyola.autarkia.core.bp.Blueprint.Outcome;
import dev.luizloyola.autarkia.core.bp.BpCompiler.Compiled;
import dev.luizloyola.autarkia.core.bp.BuildPlan.BillLine;
import dev.luizloyola.autarkia.core.bp.BuildPlan.CellKind;
import dev.luizloyola.autarkia.core.bp.Chooser.Choice;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Stage 3: pins, options, mixes, fixtures, attachments and the bill. */
class PlannerTest {

    private static final Dictionary DICT = CorpusTest.DICT;

    private static final String HEAD = """
            bp 1
            name        t
            author      t
            version     1
            orientation all
            flippable   false
            """;

    private static final Chooser NEVER = (what, choices) -> fail("nothing should be left to choose: " + what);

    private static Blueprint bind(String text) {
        Compiled compiled = BpCompiler.compile("t", text, DICT);
        assertNotNull(compiled.blueprint(), () -> "does not bind: " + compiled.diagnostics());
        return compiled.blueprint();
    }

    private static final Support CUBES = Support.solidCubes(DICT);

    private static BuildPlan plan(Blueprint bp, Map<Integer, String> pins, Chooser chooser, long seed) {
        return plan(bp, CUBES, pins, chooser, seed);
    }

    private static BuildPlan plan(Blueprint bp, Support support, Map<Integer, String> pins, Chooser chooser,
                                  long seed) {
        Diagnostics out = new Diagnostics();
        BuildPlan plan = Planner.plan(bp, DICT, support, pins, chooser, new Random(seed), out);
        assertNotNull(plan, () -> "does not plan: " + out.list());
        return plan;
    }

    private static List<String> refusals(Blueprint bp, Map<Integer, String> pins) {
        Diagnostics out = new Diagnostics();
        assertNull(Planner.plan(bp, DICT, CUBES, pins, Chooser.random(new Random(1)), new Random(1), out));
        return out.list().stream().map(d -> d.code() + ": " + d.message()).toList();
    }

    private static Outcome block(String id, String... props) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < props.length; i += 2) {
            map.put(props[i], props[i + 1]);
        }
        return new Outcome("minecraft:" + id, map);
    }

    // ── the house ───────────────────────────────────────────────────────────────────────────

    @Test
    void theHousePlansEveryCellFromItsPins() throws IOException {
        BuildPlan plan = plan(bind(ChecksAndFactsTest.house()), Map.of(1, "spruce", 2, "red", 3, "gravel"), NEVER, 1);

        assertEquals(block("spruce_log", "axis", "y"), plan.state(0, 0, 0));
        assertEquals(block("spruce_log", "axis", "x"), plan.state(0, 1, 0));
        assertEquals(block("spruce_planks"), plan.state(1, 1, 0));
        assertEquals(block("gravel"), plan.state(-1, 0, 0));
        assertEquals(CellKind.TERRAIN, plan.kind(-1, 0, 2));
        assertEquals(CellKind.AIR, plan.kind(2, 0, 2));
        assertEquals(Map.of(1, "spruce", 2, "red", 3, "gravel"), plan.bindings());
        assertEquals(1, plan.version());
    }

    @Test
    void aFixtureSaysItsPartsAndWritesTheOneLeftOut() throws IOException {
        BuildPlan plan = plan(bind(ChecksAndFactsTest.house()), Map.of(1, "oak", 2, "blue", 3, "dirt"), NEVER, 1);

        // Unsaid, a bed is its head — vanilla's default is the foot, so the plan must say so.
        assertEquals(block("blue_bed", "facing", "south", "part", "head"), plan.state(1, 1, 2));
        assertEquals(block("blue_bed", "facing", "south", "part", "foot"), plan.state(1, 1, 1));
        assertEquals(block("oak_door", "facing", "north", "half", "lower", "hinge", "right"), plan.state(1, 2, 4));
        // Drawn as air: the upper half is inferred, and written.
        assertEquals(block("oak_door", "facing", "north", "half", "upper", "hinge", "right"), plan.state(2, 2, 4));
        assertEquals(CellKind.BLOCK, plan.kind(2, 2, 4));
    }

    @Test
    void theHousesTorchFindsTheNorthWall() throws IOException {
        BuildPlan plan = plan(bind(ChecksAndFactsTest.house()), Map.of(1, "oak", 2, "blue", 3, "dirt"), NEVER, 1);
        assertEquals(block("wall_torch", "facing", "south"), plan.state(2, 2, 1));
    }

    @Test
    void theHousesBillCountsEachPlacedBlockOnce() throws IOException {
        BuildPlan plan = plan(bind(ChecksAndFactsTest.house()), Map.of(1, "spruce", 2, "red", 3, "gravel"), NEVER, 1);

        assertEquals(List.of(
                new BillLine(Set.of("minecraft:spruce_planks"), 50),
                new BillLine(Set.of("minecraft:spruce_log"), 44),
                new BillLine(Set.of("minecraft:gravel"), 10),
                new BillLine(Set.of("minecraft:red_bed"), 1),
                new BillLine(Set.of("minecraft:spruce_door"), 1),
                new BillLine(Set.of("minecraft:torch"), 1)), plan.bill());
        assertTrue(plan.itemless().isEmpty());
    }

    @Test
    void theSamePinsAndRollsMakeTheSamePlan() throws IOException {
        Blueprint bp = bind(ChecksAndFactsTest.house());
        BuildPlan a = plan(bp, Map.of(), Chooser.random(new Random(5)), 9);
        BuildPlan b = plan(bp, Map.of(), Chooser.random(new Random(5)), 9);
        assertEquals(a.bindings(), b.bindings());
        for (int layer = bp.minLayer(); layer <= bp.maxLayer(); layer++) {
            for (int z = 0; z < bp.depth(); z++) {
                for (int x = 0; x < bp.width(); x++) {
                    assertEquals(a.state(layer, x, z), b.state(layer, x, z));
                }
            }
        }
    }

    // ── pins and options ────────────────────────────────────────────────────────────────────

    @Test
    void aPinMustNameAMemberOfTheSlotsDomain() throws IOException {
        Blueprint bp = bind(ChecksAndFactsTest.house());
        List<String> crimson = refusals(bp, Map.of(1, "crimson"));
        assertEquals(1, crimson.size());
        assertTrue(crimson.get(0).startsWith("pin_domain: slot 1 takes one of"), crimson.get(0));
        assertTrue(refusals(bp, Map.of(1, "sprice")).get(0).endsWith("did you mean 'spruce'?"));
        assertTrue(refusals(bp, Map.of(9, "oak")).get(0).startsWith("pin_slot: no slot 9"));
        assertTrue(refusals(bp, Map.of(3, "stone")).get(0).startsWith("pin_domain: slot 3 takes one of coarse_dirt, "
                + "dirt, gravel"));
    }

    @Test
    void anOptionIsOfferedUniformlyOverItsTermsWithRepeatsMerged() {
        Blueprint bp = bind(HEAD + """
                materials
                  1 option oak|overworld_wood
                legend
                  A $1 planks
                layer 1
                  A
                """);
        List<Choice> offered = new ArrayList<>();
        Chooser watching = (what, choices) -> {
            assertEquals("slot 1", what);
            offered.addAll(choices);
            return 1;
        };
        BuildPlan plan = plan(bp, Map.of(), watching, 1);
        Map<String, Double> weights = new LinkedHashMap<>();
        offered.forEach(choice -> weights.put(choice.label(), choice.weight()));
        assertEquals(Set.of("oak", "spruce", "birch", "pale_oak", "bamboo"), weights.keySet());
        assertEquals(0.6, weights.get("oak"), 1e-9);
        assertEquals(0.1, weights.get("spruce"), 1e-9);
        assertEquals(Set.of("minecraft:oak_planks"), offered.get(0).blocks());
        assertEquals(block(offered.get(1).label() + "_planks"), plan.state(1, 0, 0));
    }

    @Test
    void anOptionOverAMixKeepsMixingAndSaysSo() {
        Blueprint bp = bind(HEAD + """
                palette
                  1 mix    cobblestone|stone
                  2 option $1|obsidian
                legend
                  A $2
                layer 1
                  AAAAAAAAAAAAAAAA
                """);
        BuildPlan plan = plan(bp, Map.of(), (what, choices) -> 0, 3);
        assertEquals(Map.of(2, "$1"), plan.bindings());
        Set<String> placed = new java.util.HashSet<>();
        for (int x = 0; x < 16; x++) {
            placed.add(plan.state(1, x, 0).block());
        }
        assertEquals(Set.of("minecraft:cobblestone", "minecraft:stone"), placed);
        assertEquals(List.of(new BillLine(Set.of("minecraft:cobblestone", "minecraft:stone"), 16)), plan.bill());
    }

    @Test
    void aPinCollapsesAMix() {
        Blueprint bp = bind(HEAD + """
                materials
                  3 mix oak|spruce
                legend
                  B $3 planks
                layer 1
                  BBBBBBBB
                """);
        BuildPlan plan = plan(bp, Map.of(3, "spruce"), NEVER, 1);
        for (int x = 0; x < 8; x++) {
            assertEquals(block("spruce_planks"), plan.state(1, x, 0));
        }
        assertEquals(List.of(new BillLine(Set.of("minecraft:spruce_planks"), 8)), plan.bill());
    }

    /** Format spec: {@code 2 mix $1|stone} over {@code 1 mix cobblestone|stone} is stone three times in four. */
    @Test
    void aNestedMixWeightsByTerm() {
        StringBuilder grid = new StringBuilder();
        for (int z = 0; z < 32; z++) {
            grid.append("  ").append("x".repeat(32)).append('\n');
        }
        Blueprint bp = bind(HEAD + """
                palette
                  1 mix cobblestone|stone
                  2 mix $1|stone
                legend
                  x $2
                layer 1
                """ + grid);
        BuildPlan plan = plan(bp, Map.of(), NEVER, 7);
        int stone = 0;
        for (int z = 0; z < 32; z++) {
            for (int x = 0; x < 32; x++) {
                if (plan.state(1, x, z).block().equals("minecraft:stone")) {
                    stone++;
                }
            }
        }
        assertEquals(0.75, stone / 1024.0, 0.05);
    }

    @Test
    void aMixedBedIsOneColourHeadToFoot() {
        Blueprint bp = bind(HEAD + """
                materials
                  1 mix any_color
                legend
                  B $1 bed[facing=south]
                  b $1 bed[facing=south,part=foot]
                layer 1
                  bbbbbbbb....
                  BBBBBBBBBBBB
                """);
        BuildPlan plan = plan(bp, Map.of(), NEVER, 11);
        Set<String> colours = new java.util.HashSet<>();
        for (int x = 0; x < 12; x++) {
            Outcome head = plan.state(1, x, 1);
            Outcome foot = plan.state(1, x, 0);
            assertEquals("head", head.props().get("part"));
            assertEquals("foot", foot.props().get("part"));
            assertEquals(head.block(), foot.block(), "bed " + x);
            colours.add(head.block());
        }
        assertTrue(colours.size() > 1, "twelve beds should not all roll one colour");
        assertEquals(12, plan.bill().stream().mapToInt(BillLine::count).sum());
    }

    // ── attachments ─────────────────────────────────────────────────────────────────────────

    private static Outcome single(String legend, String... layers) {
        return single(CUBES, legend, layers);
    }

    private static Outcome single(Support support, String legend, String... layers) {
        StringBuilder text = new StringBuilder(HEAD).append("legend\n  # stone\n").append(legend).append('\n');
        for (int i = 0; i < layers.length; i++) {
            text.append("layer ").append(i).append('\n');
            for (String row : layers[i].split("/")) {
                text.append("  ").append(row).append('\n');
            }
        }
        Blueprint bp = bind(text.toString());
        BuildPlan plan = plan(bp, support, Map.of(), NEVER, 1);
        for (int layer = bp.minLayer(); layer <= bp.maxLayer(); layer++) {
            for (int z = 0; z < bp.depth(); z++) {
                for (int x = 0; x < bp.width(); x++) {
                    if (bp.glyph(layer, x, z) == 'T') {
                        return plan.state(layer, x, z);
                    }
                }
            }
        }
        throw new AssertionError("no T drawn");
    }

    @Test
    void aTorchTakesAWallBeforeTheFloorNorthFirst() {
        assertEquals(block("wall_torch", "facing", "south"), single("  T torch", "#/#", "#/T"));
        assertEquals(block("wall_torch", "facing", "east"), single("  T torch", "..", "#T"));
        assertEquals(block("torch"), single("  T torch", "#", "T"));
    }

    @Test
    void aTorchWithNothingToHangFromIsRefused() {
        Blueprint bp = bind(HEAD + """
                legend
                  T torch
                layer 0
                  .
                layer 1
                  T
                """);
        List<String> refused = refusals(bp, Map.of());
        assertEquals(1, refused.size());
        assertTrue(refused.get(0).startsWith("unattached: torch has nothing to hang from"), refused.get(0));
    }

    /** A fence post holds a torch on its top and nothing on its side, as vanilla's shapes say. */
    private static final Support POSTS = (block, face, center) -> block.block().endsWith("_fence")
            ? face == Support.Face.UP && center : CUBES.holds(block, face, center);

    @Test
    void onlyWhatCanHoldItCounts() {
        assertEquals(block("torch"), single(POSTS, "  T torch\n  f oak_fence", "f", "T"));
        // Beside a post, over a floor: the post is no wall, so it stands.
        assertEquals(block("torch"), single(POSTS, "  T torch\n  f oak_fence", "##", "fT"));
        Blueprint bp = bind(HEAD + """
                legend
                  T torch
                  f oak_fence
                layer 0
                  ..
                layer 1
                  fT
                """);
        assertTrue(refusals(bp, Map.of()).get(0).startsWith("unattached: torch has nothing to hang from: no wall "
                + "or floor beside it that can hold it"));
    }

    @Test
    void aLanternHangsBeforeItStandsAndAButtonFindsItsWall() {
        assertEquals(block("lantern", "hanging", "true"), single("  T lantern", "#", "T", "#"));
        assertEquals(block("lantern", "hanging", "false"), single("  T lantern", "#", "T"));
        assertEquals(block("lantern", "hanging", "true"), single("  T lantern", ".", "T", "#"));
        assertEquals(block("stone_button", "face", "wall", "facing", "west"), single("  T stone_button", "..", "T#"));
        assertEquals(block("stone_button", "face", "ceiling"), single("  T stone_button", ".", "T", "#"));
    }

    @Test
    void whatTheAuthorSettledIsLeftAlone() {
        assertEquals(block("oak_wall_sign", "facing", "east"), single("  T oak_sign", "##", "#T"));
        assertEquals(block("oak_sign", "rotation", "4"), single("  T oak_sign[rotation=4]", "##", "#T"));
        assertEquals(block("lantern", "hanging", "true"), single("  T lantern[hanging=true]", "#", "T"));
        // A hanging sign's wall twin is a bracket, not the same sign turned.
        assertEquals(block("oak_hanging_sign"), single("  T oak_hanging_sign", "##", "#T"));
    }

    // ── placed ──────────────────────────────────────────────────────────────────────────────

    @Test
    void theAnchorIsTheFootprintsCentreOnTheGround() throws IOException {
        BuildPlan plan = plan(bind(ChecksAndFactsTest.house()), Map.of(1, "oak", 2, "blue", 3, "dirt"), NEVER, 1);
        Map<List<Integer>, Outcome> asDrawn = new LinkedHashMap<>();
        plan.forEach(Placement.AS_DRAWN, (dx, layer, dz, kind, state) -> asDrawn.put(List.of(dx, layer, dz), state));
        assertEquals(block("oak_log", "axis", "y"), asDrawn.get(List.of(-2, 0, -2)));
        assertEquals("minecraft:oak_door", asDrawn.get(List.of(0, 1, 2)).block());

        // North edge east: the south wall, and its door, now face west.
        Map<List<Integer>, Outcome> turned = new LinkedHashMap<>();
        plan.forEach(new Placement(Blueprint.Facing.EAST, false),
                (dx, layer, dz, kind, state) -> turned.put(List.of(dx, layer, dz), state));
        assertEquals("minecraft:oak_door", turned.get(List.of(-2, 1, 0)).block());
        assertEquals("minecraft:wall_torch", turned.get(List.of(1, 2, 0)).block());
    }
}
