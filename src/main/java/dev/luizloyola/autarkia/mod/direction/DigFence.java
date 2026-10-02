package dev.luizloyola.autarkia.mod.direction;

import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.nav.HandsOff;
import dev.luizloyola.autarkia.core.board.Flatten;
import dev.luizloyola.autarkia.core.board.PartyBoard;
import dev.luizloyola.autarkia.core.board.Project;
import dev.luizloyola.autarkia.core.bp.Footprint;
import dev.luizloyola.autarkia.core.builder.Structure;
import dev.luizloyola.autarkia.core.earthwork.DigDirt;
import dev.luizloyola.autarkia.core.earthwork.FlattenPlan;
import dev.luizloyola.autarkia.mod.board.PartyBoards;
import dev.luizloyola.autarkia.mod.builder.StructuresData;
import java.util.List;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import org.jspecify.annotations.Nullable;

/**
 * Where {@link DigDirt} may not scrape: the settled ground no walk cuts ({@link SettledGround}), and
 * every flatten and building site with the ring it eases into, so dirt for a pad never comes out of
 * that pad's own slope.
 */
public final class DigFence {

    private static volatile @Nullable MinecraftServer live;

    private DigFence() {
    }

    public static void init() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> live = server);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> live = null);
        DigDirt.fenceBy(DigFence::around);
    }

    static HandsOff around(Pos near, int reach) {
        MinecraftServer server = live;
        if (server == null) {
            return HandsOff.NONE;
        }
        BlockPos at = new BlockPos(near.x(), near.y(), near.z());
        List<int[]> boxes = SettledGround.boxes(server.overworld(), at, at);
        int ring = FlattenPlan.Rules.MAX_RING;
        for (PartyBoard board : PartyBoards.all(server)) {
            for (Project project : board.projects()) {
                if (project instanceof Flatten flatten) {
                    add(boxes, near, reach, flatten.area().min().x() - ring, flatten.area().min().z() - ring,
                            flatten.area().max().x() + ring, flatten.area().max().z() + ring);
                }
            }
        }
        for (List<Structure> structures : StructuresData.get(server).parties().values()) {
            for (Structure structure : structures) {
                Footprint pad = structure.pad();
                if (pad != null) {
                    add(boxes, near, reach, pad.minX() - Structure.RING, pad.minZ() - Structure.RING,
                            pad.maxX() + Structure.RING, pad.maxZ() + Structure.RING);
                }
            }
        }
        return HandsOff.columns(boxes);
    }

    /** The box when it comes within {@code reach} of {@code near}. */
    private static void add(List<int[]> boxes, Pos near, int reach, int x1, int z1, int x2, int z2) {
        if (x2 >= near.x() - reach && x1 <= near.x() + reach && z2 >= near.z() - reach && z1 <= near.z() + reach) {
            boxes.add(new int[] {x1, z1, x2, z2});
        }
    }
}
