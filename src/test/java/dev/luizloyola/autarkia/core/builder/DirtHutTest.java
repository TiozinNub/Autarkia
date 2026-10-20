package dev.luizloyola.autarkia.core.builder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.autarkia.core.bp.Blueprint;
import dev.luizloyola.autarkia.core.bp.BpCompiler;
import dev.luizloyola.autarkia.core.bp.BuildPlan;
import dev.luizloyola.autarkia.core.bp.Diagnostics;
import dev.luizloyola.autarkia.core.bp.Facts;
import dev.luizloyola.autarkia.core.bp.Placement;
import dev.luizloyola.autarkia.core.bp.Planner;
import dev.luizloyola.autarkia.core.bp.Support;
import java.io.IOException;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * The first-night hut (Luiz, 2026-10-20): dirt, cobblestone as an option, two cells inside, two
 * high, one wooden door.
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
    void dirtUnlessTheOptionGoesToCobblestone() throws IOException {
        Blueprint hut = BuildOrderTest.bind(BuildOrderTest.read(PATH));
        Map<String, Integer> plain = blocks(BuildOrderTest.plan(hut, Map.of()));
        assertEquals(30, plain.get("minecraft:dirt"), "every wall and roof block");
        assertEquals(2, plain.entrySet().stream().filter(e -> e.getKey().endsWith("_door"))
                .mapToInt(Map.Entry::getValue).sum(), "one wooden door, both halves");
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
}
