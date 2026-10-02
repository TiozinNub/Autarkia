package dev.luizloyola.autarkia.mod.builder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.JsonOps;
import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.territory.ChunkKey;
import dev.luizloyola.autarkia.core.bp.Blueprint.Facing;
import dev.luizloyola.autarkia.core.bp.Footprint;
import dev.luizloyola.autarkia.core.bp.Placement;
import dev.luizloyola.autarkia.core.builder.Structure;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** A sited building survives a save — a restart changes nothing — and knows what ground it waits on. */
class StructuresDataTest {

    private static final Structure SITED = new Structure(new UUID(4, 2), "autarkia:basic_wooden_house", 3,
            Map.of("base", "lv1", "beds", "none"), Map.of(1, "minecraft:oak_log", 2, "minecraft:spruce_planks"),
            new Pos(-188, 63, -181), new Placement(Facing.EAST, true), new Footprint(-192, -185, -184, -177),
            new Footprint(-194, -187, -182, -175),
            Structure.Phase.LEVELLING, 123_456L, "");

    @Test
    void aSitedBuildingReadsBackAsItWasWritten() {
        var json = StructuresData.STRUCTURE.encodeStart(JsonOps.INSTANCE, SITED).getOrThrow();
        Structure back = StructuresData.STRUCTURE.parse(JsonOps.INSTANCE, json).getOrThrow();

        assertEquals(SITED, back);
        Structure refused = SITED.at(Structure.Phase.REFUSED, "the pad cannot be levelled: {fluid=3}");
        assertEquals(refused, StructuresData.STRUCTURE.parse(JsonOps.INSTANCE,
                StructuresData.STRUCTURE.encodeStart(JsonOps.INSTANCE, refused).getOrThrow()).getOrThrow());
    }

    @Test
    void aBuiltBuildingKeepsWhoBuiltItAndWhen() {
        Structure built = SITED.at(Structure.Phase.BUILT, "2 steps handed back").with(new Structure.Work(
                List.of(new AgentId(new UUID(7, 1)), new AgentId(new UUID(7, 2))), 200_000L, 214_800L));
        assertEquals(built, StructuresData.STRUCTURE.parse(JsonOps.INSTANCE,
                StructuresData.STRUCTURE.encodeStart(JsonOps.INSTANCE, built).getOrThrow()).getOrThrow());
        assertEquals(built.work(), built.at(Structure.Phase.BUILT, "").work(), "a phase change keeps the work");
    }

    @Test
    void aSlotBoundAgainIsSavedBoundAgain() {
        Structure rebound = SITED.at(Structure.Phase.BUILDING, "").rebound(Map.of(1, "jungle", 2, "spruce"));
        Structure back = StructuresData.STRUCTURE.parse(JsonOps.INSTANCE,
                StructuresData.STRUCTURE.encodeStart(JsonOps.INSTANCE, rebound).getOrThrow()).getOrThrow();
        assertEquals(Map.of(1, "jungle", 2, "spruce"), back.bindings());
        assertEquals(rebound, back);
    }

    @Test
    void aGrowthKeepsWhatItGrowsFrom() {
        Structure growing = SITED.at(Structure.Phase.BUILT, "").growing(Map.of("base", "lv3", "beds", "none"), "");
        Structure back = StructuresData.STRUCTURE.parse(JsonOps.INSTANCE,
                StructuresData.STRUCTURE.encodeStart(JsonOps.INSTANCE, growing).getOrThrow()).getOrThrow();
        assertEquals(Map.of("base", "lv1", "beds", "none"), back.grownFrom());
        assertEquals(growing, back);
        assertEquals(Map.of(), back.grown("").grownFrom());
        assertEquals(Map.of("base", "lv3", "beds", "none"), back.grown("").variants());
    }

    @Test
    void itWaitsOnThePadAndTheRingAFlattenEasesInto() {
        // The pad spans x -194..-182, z -187..-175; eight more each way is -202..-174, -195..-167.
        assertEquals(ChunkKey.covering(ChunkKey.OVERWORLD, -202, -195, -174, -167), SITED.groundChunks());
        assertTrue(SITED.groundChunks().contains(ChunkKey.at(ChunkKey.OVERWORLD, -200, -170)));
    }
}
