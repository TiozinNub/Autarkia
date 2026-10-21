package dev.luizloyola.autarkia.core.builder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.autarkia.core.bp.Blueprint;
import dev.luizloyola.autarkia.core.bp.BpCompiler;
import dev.luizloyola.autarkia.core.bp.BuildPlan;
import dev.luizloyola.autarkia.core.bp.Chooser;
import dev.luizloyola.autarkia.core.bp.Diagnostics;
import dev.luizloyola.autarkia.core.bp.Facts;
import dev.luizloyola.autarkia.core.bp.Placement;
import dev.luizloyola.autarkia.core.bp.Planner;
import dev.luizloyola.autarkia.core.bp.Stock;
import dev.luizloyola.autarkia.core.bp.Support;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/**
 * The first-night hut (Luiz, 2026-10-20 and 2026-10-21): planks or dirt, cobblestone as an
 * option, bound to what the party can get now; two cells inside, two high, one wooden door.
 */
class DirtHutTest {

    private static final String PATH = "/data/autarkia/autarkia/blueprint/dirt_hut.bp";

    @Test
    void itLoadsWithNothingToWarnOf() throws IOException {
        var compiled = BpCompiler.compile("autarkia:dirt_hut", BuildOrderTest.read(PATH), BuildOrderTest.DICT);
        assertTrue(compiled.ok(), compiled.diagnostics()::toString);
        assertTrue(compiled.diagnostics().stream().toList().isEmpty(), compiled.diagnostics()::toString);
    }

    @Test
    void oneRoomTwoCellsTwoHighBehindOneDoor() throws IOException {
        Blueprint hut = BuildOrderTest.bind(BuildOrderTest.read(PATH));
        Facts facts = Facts.of(hut, BuildOrderTest.DICT);
        assertEquals(1, facts.rooms(), "shut all round once the door is");
        assertEquals(4, facts.roomCells(), "two cells, two high");
        assertEquals(1, facts.entrances().size());
    }

    @Test
    void planksFirstWithAWoodenDoorAndCobblestoneOrDirtAsOptions() throws IOException {
        Blueprint hut = BuildOrderTest.bind(BuildOrderTest.read(PATH));
        Map<String, Integer> plain = blocks(BuildOrderTest.plan(hut, Map.of()));
        String planks = only(plain, "_planks");
        assertEquals(30, plain.get(planks), "every wall and roof block");
        assertEquals(2, plain.get(planks.replace("_planks", "_door")), "a door of the same wood, both halves");
        assertEquals(2, plain.size());
        Diagnostics out = new Diagnostics();
        BuildPlan cobbled = Planner.plan(hut, BuildOrderTest.DICT, Support.solidCubes(BuildOrderTest.DICT),
                Map.of(1, "cobblestone"), Map.of(), (what, choices) -> 0, new Random(3), out);
        assertTrue(cobbled != null, out.list()::toString);
        assertEquals(30, blocks(cobbled).get("minecraft:cobblestone"), "the option, every wall block");
    }

    private static Map<String, Integer> blocks(BuildPlan plan) {
        Map<String, Integer> out = new TreeMap<>();
        plan.forEach(Placement.AS_DRAWN, (dx, layer, dz, kind, state) -> {
            if (kind == BuildPlan.CellKind.BLOCK && state != null) {
                out.merge(state.block(), 1, Integer::sum);
            }
        });
        return out;
    }

    /** Only planks can be got: dirt has no way off flat ground, cobblestone waits for the Stone Age. */
    private static final Predicate<Set<String>> WOOD_AGE = blocks -> blocks.stream().allMatch(
            block -> block.endsWith("_planks") || block.endsWith("_door"));

    @Test
    void aWoodAgePartyWithNothingGetsPlanksOfTheWoodGrowingRound() throws IOException {
        Blueprint hut = BuildOrderTest.bind(BuildOrderTest.read(PATH));
        Map<String, Integer> got = blocks(held(hut, Map.of(), Map.of("minecraft:oak_log", 20), WOOD_AGE));
        assertEquals(30, got.get("minecraft:oak_planks"), "planks, about eight logs (Luiz, 2026-10-21)");
        assertEquals(2, got.get("minecraft:oak_door"), "the door's wood and the walls' agree");
    }

    @Test
    void enoughDirtHeldIsDirtAndLogsOnlyArePlanks() throws IOException {
        Blueprint hut = BuildOrderTest.bind(BuildOrderTest.read(PATH));
        assertEquals(30, blocks(held(hut, Map.of("minecraft:dirt", 30), Map.of(), WOOD_AGE))
                .get("minecraft:dirt"), "it holds a hut's worth");
        assertEquals(30, blocks(held(hut, Map.of("minecraft:dirt", 12, "minecraft:oak_log", 8), Map.of(),
                WOOD_AGE)).get("minecraft:oak_planks"), "logs only, and too little dirt");
        assertEquals(30, blocks(held(hut, Map.of("minecraft:cobblestone", 29), Map.of(), WOOD_AGE))
                .entrySet().stream().filter(e -> e.getKey().endsWith("_planks")).mapToInt(Map.Entry::getValue)
                .sum(), "some cobblestone, not a hut's worth: never the way it cannot get");
        assertEquals(30, blocks(held(hut, Map.of("minecraft:cobblestone", 30), Map.of(), WOOD_AGE))
                .get("minecraft:cobblestone"), "held in full, cobblestone is fine");
    }

    @Test
    void aWayItCannotGetIsPassedForOneItCan() throws IOException {
        Blueprint hut = BuildOrderTest.bind(BuildOrderTest.read(PATH));
        Predicate<Set<String>> dirtOnly = blocks -> blocks.stream().allMatch(
                block -> block.equals("minecraft:dirt") || block.endsWith("_door"));
        assertEquals(30, blocks(held(hut, Map.of(), Map.of("minecraft:oak_log", 20), dirtOnly))
                .get("minecraft:dirt"), "dirt in a store seen, no logs to be had: not the planks first in line");
    }

    private static String only(Map<String, Integer> blocks, String suffix) {
        List<String> found = blocks.keySet().stream().filter(id -> id.endsWith(suffix)).toList();
        assertEquals(1, found.size(), found::toString);
        return found.get(0);
    }

    private static BuildPlan held(Blueprint hut, Map<String, Integer> items, Map<String, Integer> growing,
                                  Predicate<Set<String>> canGet) {
        Diagnostics out = new Diagnostics();
        BuildPlan plan = Planner.plan(hut, BuildOrderTest.DICT, Support.solidCubes(BuildOrderTest.DICT), Map.of(),
                Map.of(), Chooser.gettable(Stock.of(BuildOrderTest.DICT, items, growing), canGet,
                        (what, choices) -> 0), new Random(3), out);
        assertTrue(plan != null, out.list()::toString);
        return plan;
    }
}
