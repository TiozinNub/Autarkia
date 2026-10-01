package dev.luizloyola.autarkia.core.bp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.territory.ChunkKey;
import dev.luizloyola.autarkia.core.bp.Blueprint.Facing;
import dev.luizloyola.autarkia.core.bp.BpCompiler.Compiled;
import dev.luizloyola.autarkia.core.bp.BuildPlan.CellKind;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FootprintTest {

    private static final Dictionary DICT = TestBlocks.dictionary();

    private static BuildPlan house() throws IOException {
        String text;
        try (InputStream in = FootprintTest.class.getResourceAsStream(
                "/data/autarkia/autarkia/blueprint/basic_wooden_house.bp")) {
            assertNotNull(in);
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Compiled compiled = BpCompiler.compile("t", text, DICT);
        assertTrue(compiled.ok(), compiled.diagnostics()::toString);
        Diagnostics out = new Diagnostics();
        BuildPlan plan = Planner.plan(compiled.blueprint(), DICT, Support.solidCubes(DICT), Map.of(),
                Map.of("beds", "2", "base", "lv1", "attic", "has"), (what, choices) -> 0, new Random(3), out);
        assertNotNull(plan, out.list()::toString);
        return plan;
    }

    @Test
    void aHouseAcrossAChunkEdgeStandsOnBothChunks() throws IOException {
        BuildPlan plan = house();
        Footprint at = Footprint.of(15, 8, plan, Placement.AS_DRAWN);
        assertEquals(plan.width(), at.maxX() - at.minX() + 1);
        assertEquals(plan.depth(), at.maxZ() - at.minZ() + 1);
        assertEquals(Set.of(new ChunkKey(ChunkKey.OVERWORLD, 0, 0), new ChunkKey(ChunkKey.OVERWORLD, 1, 0)),
                at.chunks(ChunkKey.OVERWORLD));
    }

    /** The doorstep is beside the door, on its open side away from the house, at every facing. */
    @Test
    void theYardGoesOutsideTheDoorHoweverTheHouseFaces() throws IOException {
        BuildPlan plan = house();
        for (Facing facing : Facing.values()) {
            Placement placement = new Placement(facing, false);
            int[] step = Footprint.doorstep(plan, placement, DICT).orElseThrow();
            Map<String, CellKind> kinds = new HashMap<>();
            int[] door = {Integer.MIN_VALUE, 0, 0};
            plan.forEach(placement, (dx, layer, dz, kind, state) -> {
                kinds.put(dx + "," + layer + "," + dz, kind);
                if (door[0] == Integer.MIN_VALUE && state != null && state.block().endsWith("_door")) {
                    door[0] = dx;
                    door[1] = layer;
                    door[2] = dz;
                }
            });
            assertEquals(door[1], step[1], facing + ": level with the door's lower half");
            assertEquals(1, Math.abs(step[0] - door[0]) + Math.abs(step[2] - door[2]), facing + ": beside it");
            CellKind there = kinds.get(step[0] + "," + step[1] + "," + step[2]);
            assertTrue(there == null || there == CellKind.AIR || there == CellKind.KEEP, facing + ": open");
            Footprint box = Footprint.of(0, 0, plan, placement);
            double midX = (box.minX() + box.maxX()) / 2.0;
            double midZ = (box.minZ() + box.maxZ()) / 2.0;
            assertTrue(Math.hypot(step[0] - midX, step[2] - midZ) > Math.hypot(door[0] - midX, door[2] - midZ),
                    facing + ": outside, not in the room");
        }
    }
}
