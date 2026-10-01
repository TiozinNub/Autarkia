package dev.luizloyola.autarkia.core.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.board.WorkItem;
import dev.luizloyola.anima.core.brain.knowledge.BlockKind;
import dev.luizloyola.anima.core.brain.knowledge.BlockProbe;
import dev.luizloyola.anima.core.brain.knowledge.Region;
import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ClearPlantsTest {

    private static final Region CHUNK = new Region(new Pos(0, 48, 0), new Pos(15, 112, 15));

    private final BoardBrainContext ctx = new BoardBrainContext();

    @AfterEach
    void unplant() {
        Plants.install(id -> false);
    }

    @Test
    void aChunkIsFourStripsAndDoneWhenEachIs() {
        ClearPlants project = new ClearPlants(CHUNK, 0.5);
        assertEquals(4, project.open().size());
        for (WorkItem item : project.open()) {
            assertFalse(project.finished());
            project.completed(item, ctx);
        }
        assertTrue(project.finished());
        assertTrue(project.open().isEmpty());
    }

    @Test
    void aStripThreeMembersFailedAtIsWrittenOffAndOneMemberCountsOnce() {
        ClearPlants project = new ClearPlants(CHUNK, 0.5);
        WorkItem strip = project.open().get(0);
        AgentId ari = AgentId.random();
        project.failed(strip, ari, ctx);
        project.failed(strip, ari, ctx);
        project.failed(strip, AgentId.random(), ctx);
        assertEquals(4, project.open().size(), "two of us, not three");
        project.failed(strip, AgentId.random(), ctx);
        assertEquals(3, project.open().size(), "written off, so a plant nobody reaches never keeps it open");
    }

    @Test
    void aRestartChangesNothing() {
        ClearPlants project = new ClearPlants(CHUNK, 0.5);
        project.completed(project.open().get(1), ctx);
        project.failed(project.open().get(0), AgentId.random(), ctx);
        ClearPlants back = ClearPlants.restore((ClearPlants.State) project.snapshot(), 0L).orElseThrow();
        assertEquals(project.snapshot(), back.snapshot());
        assertEquals(3, back.open().size());
        WorkItem first = back.open().get(0);
        assertEquals(first, back.itemFor(back.keyOf(first).orElseThrow()).orElseThrow(),
                "a hold saved by its key comes back to the same strip");
    }

    @Test
    void theStripsPlantsAreReadAtTheirTopsNearestFirst() {
        Plants.install(id -> id.equals("minecraft:short_grass") || id.equals("minecraft:poppy"));
        Map<Pos, String> world = Map.of(
                new Pos(3, 64, 1), "minecraft:poppy",
                new Pos(1, 64, 0), "minecraft:short_grass",
                new Pos(5, 64, 2), "minecraft:stone",
                new Pos(9, 30, 3), "minecraft:poppy");
        List<Pos> plants = ClearStrip.plants(new Region(new Pos(0, 48, 0), new Pos(15, 112, 3)),
                probe(world), new Pos(0, 65, 0));
        assertEquals(List.of(new Pos(1, 64, 0), new Pos(3, 64, 1)), plants,
                "stone is not a plant, and a plant below the box is not this box's");
    }

    @Test
    void grassUnderACanopyIsStillFound() {
        Plants.install(id -> id.equals("minecraft:short_grass"));
        Map<Pos, String> world = Map.of(
                new Pos(2, 64, 2), "minecraft:short_grass",
                new Pos(2, 70, 2), "minecraft:oak_leaves");
        assertEquals(List.of(new Pos(2, 64, 2)), ClearStrip.plants(
                new Region(new Pos(0, 48, 0), new Pos(15, 112, 3)), probe(world), new Pos(0, 65, 0)));
    }

    /** A flat world at y 63 with these blocks on top; a column's top is its highest block. */
    private static BlockProbe probe(Map<Pos, String> blocks) {
        Set<Pos> cells = blocks.keySet();
        return new BlockProbe() {
            @Override
            public int surfaceY(int x, int z) {
                return topY(x, z);
            }

            @Override
            public int groundY(int x, int z) {
                return 63;
            }

            @Override
            public boolean empty(int x, int y, int z) {
                return !blocks.containsKey(new Pos(x, y, z)) && y > 63;
            }

            @Override
            public int topY(int x, int z) {
                int top = 63;
                for (Pos cell : cells) {
                    if (cell.x() == x && cell.z() == z) {
                        top = Math.max(top, cell.y());
                    }
                }
                return top;
            }

            @Override
            public BlockKind at(int x, int y, int z) {
                return BlockKind.AIR;
            }

            @Override
            public String idAt(int x, int y, int z) {
                return blocks.getOrDefault(new Pos(x, y, z), y <= 63 ? "minecraft:dirt" : "minecraft:air");
            }

            @Override
            public Sight sightAt(int x, int y, int z) {
                return Sight.CLEAR;
            }

            @Override
            public boolean visibleFromEyes(Pos target) {
                return true;
            }

            @Override
            public boolean sightClearBetween(Pos from, Pos to) {
                return true;
            }
        };
    }
}
