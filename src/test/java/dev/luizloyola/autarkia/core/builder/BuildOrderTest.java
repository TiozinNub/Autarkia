package dev.luizloyola.autarkia.core.builder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.autarkia.core.bp.Blueprint;
import dev.luizloyola.autarkia.core.bp.BpCompiler;
import dev.luizloyola.autarkia.core.bp.BpCompiler.Compiled;
import dev.luizloyola.autarkia.core.bp.BuildPlan;
import dev.luizloyola.autarkia.core.bp.BuildPlan.CellKind;
import dev.luizloyola.autarkia.core.bp.Diagnostics;
import dev.luizloyola.autarkia.core.bp.Dictionary;
import dev.luizloyola.autarkia.core.bp.Planner;
import dev.luizloyola.autarkia.core.bp.Support;
import dev.luizloyola.autarkia.core.bp.TestBlocks;
import dev.luizloyola.autarkia.core.bp.Variants;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BuildOrderTest {

    static final Dictionary DICT = TestBlocks.dictionary();
    private static final Support CUBES = Support.solidCubes(DICT);
    private static final List<String> SHIPPED = List.of("/data/autarkia/autarkia/blueprint/basic_wooden_house.bp",
            "/data/autarkia/autarkia/blueprint/growing_cottage.bp", "/data/autarkia/autarkia/blueprint/road_segment.bp",
            "/bp/worked_house.bp");

    static String read(String path) throws IOException {
        try (InputStream in = BuildOrderTest.class.getResourceAsStream(path)) {
            assertNotNull(in, path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static Blueprint bind(String text) {
        Compiled compiled = BpCompiler.compile("t", text, DICT);
        assertTrue(compiled.ok(), compiled.diagnostics()::toString);
        return compiled.blueprint();
    }

    static BuildPlan plan(Blueprint bp, Map<String, String> variants) {
        Diagnostics out = new Diagnostics();
        BuildPlan plan = Planner.plan(bp, DICT, CUBES, Map.of(), variants, (what, choices) -> 0, new Random(3), out);
        assertNotNull(plan, out.list()::toString);
        return plan;
    }

    /** Every group pinned, so the plan is that selection and nothing rolled. */
    static Map<String, String> pins(Blueprint bp, Variants.Selection selection) {
        Map<String, String> pins = new LinkedHashMap<>();
        for (Variants.Group group : bp.variants().groups()) {
            pins.put(group.name(), selection.chosen().getOrDefault(group.name(), "none"));
        }
        return pins;
    }

    /**
     * Every selection of every shipped blueprint: each placed cell in exactly one step, every step
     * placed, each after what holds it and from a stand that reaches it, and the doors and the decor
     * last — nothing structural goes up once the doors are in.
     */
    @Test
    void everyShippedSelectionIsSplitAndOrdered() throws IOException {
        for (String path : SHIPPED) {
            Blueprint bp = bind(read(path));
            List<Variants.Selection> selections = bp.variants().isEmpty() ? List.of(Variants.Selection.BASE)
                    : bp.variants().selections();
            for (Variants.Selection selection : selections) {
                BuildPlan plan = plan(bp, pins(bp, selection));
                String what = path.substring(path.lastIndexOf('/') + 1) + " " + plan.variants();
                List<Step> steps = Sections.of(plan, DICT);
                Set<Cell> covered = new HashSet<>();
                for (Step step : steps) {
                    for (Cell cell : step.cells()) {
                        assertTrue(covered.add(cell), what + ": " + cell + " twice");
                    }
                }
                for (int layer = plan.minLayer(); layer <= plan.maxLayer(); layer++) {
                    for (int z = 0; z < plan.depth(); z++) {
                        for (int x = 0; x < plan.width(); x++) {
                            assertEquals(plan.kind(layer, x, z) == CellKind.BLOCK,
                                    covered.contains(new Cell(layer, x, z)), what + ": " + new Cell(layer, x, z));
                        }
                    }
                }
                BuildOrder.Result result = BuildOrder.prove(plan, DICT);
                assertTrue(result.complete(), what + ": unplaced " + result.unplaced());
                assertHeldAndInReach(plan, result, what);
                boolean doorsIn = false;
                for (BuildOrder.Placed placed : result.order()) {
                    Section section = placed.step().section();
                    doorsIn |= section.rank() >= Section.DOORS.rank();
                    assertTrue(!doorsIn || section.rank() >= Section.DOORS.rank(), what + ": " + placed
                            + " after the doors");
                }
            }
        }
    }

    /** The basic house goes up floor, walls, lights, then the roof with its gables, doors, decor. */
    @Test
    void theBasicHouseGoesUpInItsSections() throws IOException {
        Blueprint bp = bind(read(SHIPPED.get(0)));
        BuildPlan plan = plan(bp, Map.of("beds", "2", "base", "lv1", "attic", "has"));
        List<Section> runs = new ArrayList<>();
        for (BuildOrder.Placed placed : BuildOrder.prove(plan, DICT).order()) {
            if (runs.isEmpty() || runs.get(runs.size() - 1) != placed.step().section()) {
                runs.add(placed.step().section());
            }
        }
        assertEquals(List.of(Section.FLOOR, Section.WALLS, Section.LIGHTS, Section.CEILING),
                runs.subList(0, 4), runs::toString);
        assertEquals(List.of(Section.DOORS, Section.INTERIOR), runs.subList(runs.size() - 2, runs.size()),
                runs::toString);
    }

    /** Whatever hangs goes up after what holds it, and every block from a stand that reaches it. */
    static void assertHeldAndInReach(BuildPlan plan, BuildOrder.Result result, String what) {
        Set<Cell> up = new HashSet<>();
        for (BuildOrder.Placed placed : result.order()) {
            Step step = placed.step();
            Cell holder = step.holder();
            if (holder != null) {
                boolean ground = plan.contains(holder.layer(), holder.x(), holder.z())
                        ? plan.kind(holder.layer(), holder.x(), holder.z()) == CellKind.TERRAIN
                        || plan.kind(holder.layer(), holder.x(), holder.z()) == CellKind.KEEP && holder.layer() <= 0
                        : holder.layer() <= 0;
                assertTrue(ground || up.contains(holder), what + ": " + step + " before its holder");
            }
            Cell stand = placed.stand();
            double dx = step.cell().x() - stand.x();
            double dy = step.cell().layer() + 0.5 - (stand.layer() + BuildOrder.EYE);
            double dz = step.cell().z() - stand.z();
            assertTrue(Math.sqrt(dx * dx + dy * dy + dz * dz) <= BuildOrder.REACH, what + ": " + placed);
            assertTrue(!step.cells().contains(stand) && !step.cells().contains(stand.offset(1, 0, 0)),
                    what + ": stands in " + placed);
            up.addAll(step.cells());
        }
    }

    /** A room with no door: its bed goes in before the last block that shuts it. */
    @Test
    void aRoomWithNoDoorGetsItsBedBeforeItIsShut() {
        BuildPlan plan = plan(bind("""
                bp 1
                name        box
                author      t
                version     1
                orientation all
                flippable   false

                legend
                  # stone
                  B red_bed
                  b red_bed[part=foot]

                layer 0
                  #####
                  #####
                  #####
                  #####
                  #####

                layer 1
                  #####
                  #.B.#
                  #.b.#
                  #...#
                  #####

                layer 2 3
                  #####
                  #...#
                  #...#
                  #...#
                  #####

                layer 4
                  #####
                  #####
                  #####
                  #####
                  #####
                """), Map.of());
        BuildOrder.Result result = BuildOrder.prove(plan, DICT);
        assertTrue(result.complete(), result.unplaced()::toString);
        assertHeldAndInReach(plan, result, "box");
        int bed = -1;
        int lastShell = -1;
        for (int k = 0; k < result.order().size(); k++) {
            Step step = result.order().get(k).step();
            if (step.state().block().endsWith("_bed")) {
                bed = k;
            } else if (step.cell().layer() >= 1) {
                lastShell = k;
            }
        }
        assertTrue(bed >= 0 && bed < lastShell, "bed " + bed + ", room shut at " + lastShell);
    }
}
