package dev.luizloyola.autarkia.core.bp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.autarkia.core.bp.Blueprint.Outcome;
import dev.luizloyola.autarkia.core.bp.BpCompiler.Compiled;
import dev.luizloyola.autarkia.core.bp.BuildPlan.CellKind;
import dev.luizloyola.autarkia.core.bp.Capture.Box;
import dev.luizloyola.autarkia.core.bp.Capture.Headers;
import dev.luizloyola.autarkia.core.bp.Capture.Written;
import dev.luizloyola.autarkia.core.bp.Variants.Selection;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Capture's round trip: a plan stood in the world, read back, written, read and planned again,
 * places what stood — the reader spec's golden, cell for cell.
 */
class CaptureTest {

    private static final Dictionary DICT = CorpusTest.DICT;
    private static final Support CUBES = Support.solidCubes(DICT);

    private static Blueprint bind(String text) {
        Compiled compiled = BpCompiler.compile("t", text, DICT);
        assertNotNull(compiled.blueprint(), () -> compiled.diagnostics() + "\n" + text);
        return compiled.blueprint();
    }

    private static String shipped(String name) throws IOException {
        try (InputStream in = CaptureTest.class.getResourceAsStream("/data/autarkia/autarkia/blueprint/" + name)) {
            assertNotNull(in, name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static BuildPlan plan(Blueprint bp, Map<String, String> variants, Map<Integer, String> pins) {
        Diagnostics out = new Diagnostics();
        BuildPlan plan = Planner.plan(bp, DICT, CUBES, pins, variants, (what, choices) -> 0, new Random(3), out);
        assertNotNull(plan, out.list()::toString);
        return plan;
    }

    /** A plan as the world would hold it: its blocks, air where it clears, dirt where it wants ground. */
    private static Box world(BuildPlan plan) {
        int layers = plan.maxLayer() - plan.minLayer() + 1;
        Outcome[][][] cells = new Outcome[layers][plan.depth()][plan.width()];
        for (int layer = plan.minLayer(); layer <= plan.maxLayer(); layer++) {
            for (int z = 0; z < plan.depth(); z++) {
                for (int x = 0; x < plan.width(); x++) {
                    Outcome state = plan.state(layer, x, z);
                    cells[layer - plan.minLayer()][z][x] = plan.kind(layer, x, z) == CellKind.TERRAIN
                            ? new Outcome("minecraft:dirt", Map.of())
                            : state == null ? null : Capture.normalise(state.block(), state.props(), DICT);
                }
            }
        }
        return new Box(plan.width(), plan.depth(), plan.minLayer(), cells);
    }

    private static void assertPlaces(Box expected, BuildPlan plan) {
        for (int layer = expected.minLayer(); layer <= expected.maxLayer(); layer++) {
            for (int z = 0; z < expected.depth(); z++) {
                for (int x = 0; x < expected.width(); x++) {
                    Outcome state = plan.state(layer, x, z);
                    Outcome placed = state == null ? null : Capture.normalise(state.block(), state.props(), DICT);
                    assertEquals(expected.at(layer, x, z), placed, "layer " + layer + " (" + x + "," + z + ")");
                }
            }
        }
    }

    @Test
    void theHouseComesBackAsItStood() throws IOException {
        BuildPlan stood = plan(bind(shipped("basic_wooden_house.bp")), Map.of(),
                Map.of(1, "spruce", 2, "red", 3, "gravel"));
        Box world = world(stood);
        Written written = Capture.write(world, DICT, CUBES, Headers.fresh("house", "test"));
        assertTrue(written.problems().isEmpty(), written.problems()::toString);
        String text = written.text();
        assertTrue(text.contains("\n  1 option spruce\n"), text);
        assertTrue(text.contains("\n  2 option red\n"), text);
        assertTrue(text.contains(" $1 planks\n"), text);
        assertTrue(text.contains("\norientation all ") && text.contains("// captured: which ways may it face?"), text);
        assertPlaces(world, plan(bind(text), Map.of(), Map.of()));
    }

    @Test
    void aStandingTorchByAWallIsWrittenToStand() {
        Blueprint bp = bind("""
                bp 1
                name        t
                author      t
                version     1
                orientation all
                flippable   false
                legend
                  # stone
                  T torch[attach=floor]
                  L torch
                layer 0
                  ####
                layer 1
                  #T.L
                """);
        BuildPlan stood = plan(bp, Map.of(), Map.of());
        assertEquals(new Outcome("minecraft:torch", Map.of()), stood.state(1, 1, 0), "attach=floor stands");
        Box world = world(stood);
        String text = Capture.write(world, DICT, CUBES, Headers.fresh("torches", "test")).text();
        assertTrue(text.contains(" torch[attach=floor]\n"), text);
        assertTrue(text.contains(" torch\n"), text + ": the one with no wall beside needs no word");
        assertPlaces(world, plan(bind(text), Map.of(), Map.of()));
    }

    @Test
    void aStateIsWrittenAsTheFileShouldSayIt() {
        assertNull(Capture.normalise("minecraft:cave_air", Map.of(), DICT));
        assertEquals(new Outcome("minecraft:red_bed", Map.of("facing", "east")), Capture.normalise("minecraft:red_bed",
                Map.of("facing", "east", "part", "head", "occupied", "false"), DICT));
        assertEquals(new Outcome("minecraft:red_bed", Map.of("part", "foot")), Capture.normalise("minecraft:red_bed",
                Map.of("facing", "north", "part", "foot", "occupied", "false"), DICT));
        assertEquals(new Outcome("minecraft:oak_stairs", Map.of("facing", "east")), Capture.normalise(
                "minecraft:oak_stairs", Map.of("facing", "east", "half", "bottom", "shape", "outer_left",
                        "waterlogged", "false"), DICT));
        assertEquals(new Outcome("minecraft:lantern", Map.of("hanging", "false")), Capture.normalise(
                "minecraft:lantern", Map.of("hanging", "false", "waterlogged", "false"), DICT));
    }

    /** A hatch left open is the builder's call; the redstone that holds it open is the world's. */
    @Test
    void whatTheWorldSetsIsLeftOutAndWhatABuilderSetsStays() {
        assertEquals(new Outcome("minecraft:oak_trapdoor", Map.of("facing", "east", "open", "true")),
                Capture.normalise("minecraft:oak_trapdoor", Map.of("facing", "east", "half", "bottom", "open", "true",
                        "powered", "true"), DICT));
        String[][] world = {{"red_bed", "occupied", "true"}, {"oak_door", "powered", "true"},
                {"oak_fence_gate", "in_wall", "true"}, {"wheat", "age", "7"}, {"oak_sapling", "stage", "1"},
                {"farmland", "moisture", "7"}, {"furnace", "lit", "true"}, {"redstone_lamp", "lit", "true"},
                {"deepslate_redstone_ore", "lit", "true"}, {"waxed_copper_bulb", "lit", "true"},
                {"tripwire_hook", "attached", "true"}, {"water", "level", "3"}, {"scaffolding", "distance", "2"},
                {"piston", "extended", "true"}, {"dispenser", "triggered", "true"},
                {"oak_shelf", "side_chain", "left"}};
        for (String[] state : world) {
            assertTrue(Binder.computed("minecraft:" + state[0], state[1], state[2]), String.join(" ", state));
        }
        String[][] builder = {{"oak_door", "open", "true"}, {"lever", "powered", "true"}, {"candle", "lit", "true"},
                {"campfire", "lit", "false"}, {"oak_hanging_sign", "attached", "true"},
                {"water_cauldron", "level", "2"}, {"composter", "level", "4"}, {"oak_fence_gate", "open", "true"},
                {"oak_stairs", "waterlogged", "true"}};
        for (String[] state : builder) {
            assertFalse(Binder.computed("minecraft:" + state[0], state[1], state[2]), String.join(" ", state));
        }
    }

    @Test
    void moreBlocksThanALegendHoldsIsRefused() {
        List<String> ids = DICT.blocks().keySet().stream().filter(id -> !id.endsWith(":air")).limit(70).toList();
        Outcome[][][] cells = new Outcome[1][1][ids.size()];
        for (int x = 0; x < ids.size(); x++) {
            cells[0][0][x] = new Outcome(ids.get(x), Map.of());
        }
        Written written = Capture.write(new Box(ids.size(), 1, 0, cells), DICT, CUBES, Headers.fresh("x", "t"));
        assertNull(written.text());
        assertTrue(written.problems().get(0).startsWith("the box holds more than 64 different blocks"));
    }

    @Test
    void theCapturesWordsComeInAnyOrder() {
        Diagnostics out = new Diagnostics();
        Capture.Args args = Capture.Args.parse("ground -3 as beds.two", out);
        assertTrue(out.list().isEmpty(), out.list()::toString);
        assertEquals(new Capture.Args(false, "beds", "two", -3), args);
        Capture.Args.parse("replace as beds.two", out);
        Capture.Args.parse("sideways", out);
        assertEquals(List.of("capture_word", "capture_word"), out.list().stream().map(Diagnostic::code).toList());
    }

    // ── variants ────────────────────────────────────────────────────────────────────────────

    @Test
    void aVariantIsWhatChangedAndNothingElse() throws IOException {
        Blueprint cottage = bind(shipped("growing_cottage.bp"));
        Map<Integer, String> pins = Map.of(1, "spruce", 2, "red");
        Map<String, String> one = Map.of("beds", "one", "wing", "none", "workshop", "none", "cellar", "none");
        Map<String, String> two = Map.of("beds", "two", "wing", "none", "workshop", "none", "cellar", "none");
        Box small = world(plan(cottage, one, pins));
        Box bigger = world(plan(cottage, two, pins));

        String base = Capture.write(small, DICT, CUBES, Headers.fresh("cabin", "test")).text();
        Written grown = Capture.variant(base, bind(base), bigger, "beds", "two", DICT, CUBES);
        assertTrue(grown.problems().isEmpty(), grown.problems()::toString);
        String text = grown.text();
        assertTrue(text.contains("\ngroups\n  beds optional  two\n"), text);
        assertTrue(text.contains("\nlayer 1 beds.two\n"), text);
        String grid = text.substring(text.indexOf("\nlayer 1 beds.two\n") + "\nlayer 1 beds.two\n".length());
        assertEquals(1, grid.chars().filter(c -> c == 'B').count(), grid);
        assertEquals(1, grid.chars().filter(c -> c == 'b').count(), grid);
        assertEquals(grid.length(), grid.chars().filter(c -> "?Bb \n".indexOf(c) >= 0).count(),
                "the variant draws the second bed and nothing else:\n" + grid);

        Blueprint cabin = bind(text);
        assertPlaces(small, plan(cabin, Map.of("beds", "none"), Map.of()));
        assertPlaces(bigger, plan(cabin, Map.of("beds", "two"), Map.of()));
        assertEquals(new Selection(Map.of("beds", "two")), cabin.compose(new Selection(Map.of("beds", "two")))
                .selection());

        // Captured again, the variant's grids are replaced rather than added to.
        String again = Capture.variant(text, cabin, bigger, "beds", "two", DICT, CUBES).text();
        assertEquals(1, again.lines().filter(line -> line.startsWith("layer 1 beds.two")).count(), again);
        bind(again);
    }

    /**
     * The shipped cottage draws its door's lower half and a plain torch; the world holds both halves
     * and a wall torch, in spruce while the file says any wood. None of that is a change.
     */
    @Test
    void aHandDrawnBaseCountsWhatItInfersAsDrawn() throws IOException {
        String text = shipped("growing_cottage.bp");
        Blueprint cottage = bind(text);
        Box world = world(plan(cottage, Map.of("beds", "two", "wing", "none", "workshop", "none", "cellar", "none"),
                Map.of(1, "spruce", 2, "red")));
        Written written = Capture.variant(text, cottage, world, "beds", "two", DICT, CUBES);
        assertTrue(written.problems().isEmpty(), written.problems()::toString);
        String grid = written.text().substring(written.text().indexOf("\nlayer 1 beds.two\n")
                + "\nlayer 1 beds.two\n".length());
        assertEquals(2, grid.chars().filter(c -> c == 'B').count(), grid);
        assertEquals(2, grid.chars().filter(c -> c == 'b').count(), grid);
        assertEquals(grid.length(), grid.chars().filter(c -> "?Bb \n".indexOf(c) >= 0).count(), grid);
        bind(written.text());
    }

    @Test
    void aVariantOverADifferentBoxIsRefused() throws IOException {
        Blueprint house = bind(shipped("basic_wooden_house.bp"));
        Box narrow = new Box(3, 5, 0, new Outcome[1][5][3]);
        Written refused = Capture.variant(shipped("basic_wooden_house.bp"), house, narrow, "beds", "two", DICT, CUBES);
        assertNull(refused.text());
        assertTrue(refused.problems().get(0).contains("a variant is captured over the same box"));
    }
}
