package dev.luizloyola.autarkia.mod.direction;

import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.nav.HandsOff;
import dev.luizloyola.anima.core.social.PartyId;
import dev.luizloyola.anima.core.social.PlaceRow;
import dev.luizloyola.anima.core.territory.ChunkKey;
import dev.luizloyola.anima.core.territory.Territory;
import dev.luizloyola.anima.mod.social.PlacesData;
import dev.luizloyola.anima.mod.territory.Territories;
import dev.luizloyola.autarkia.core.board.Flatten;
import dev.luizloyola.autarkia.core.board.PartyBoard;
import dev.luizloyola.autarkia.core.board.Project;
import dev.luizloyola.autarkia.core.bp.Footprint;
import dev.luizloyola.autarkia.core.builder.Structure;
import dev.luizloyola.autarkia.core.direction.Home;
import dev.luizloyola.autarkia.core.direction.SettledFence;
import dev.luizloyola.autarkia.core.earthwork.FlattenPlan;
import dev.luizloyola.autarkia.mod.board.PartyBoards;
import dev.luizloyola.autarkia.mod.builder.StructuresData;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * The ground a party has made its own, as {@link SettledFence} keeps it: every party's area, a
 * margin round every store and workbench, and every flatten and building site with the ring it
 * eases into. Roads and fields join it when anything records them.
 */
public final class SettledGround {

    /** How far past a route's ends a plot or a place is still asked about: a route bends. */
    private static final int REACH = 96;
    /** Columns kept whole round a store or a workbench, so no deck is laid against one. */
    private static final int PLACE_MARGIN = 1;

    private SettledGround() {
    }

    static Set<ChunkKey> overworld(Set<ChunkKey> area) {
        Set<ChunkKey> overworld = new HashSet<>();
        for (ChunkKey chunk : area) {
            if (chunk.dimension().equals(ChunkKey.OVERWORLD)) {
                overworld.add(chunk);
            }
        }
        return overworld;
    }

    /** Anima's {@code WorkFence} rule. HOME and the places are the overworld's. */
    public static HandsOff around(ServerLevel level, BlockPos start, BlockPos goal) {
        MinecraftServer server = level.getServer();
        if (level != server.overworld()) {
            return HandsOff.NONE;
        }
        return within(server, Math.min(start.getX(), goal.getX()) - REACH,
                Math.min(start.getZ(), goal.getZ()) - REACH, Math.max(start.getX(), goal.getX()) + REACH,
                Math.max(start.getZ(), goal.getZ()) + REACH);
    }

    /** The overworld's fence over the columns {@code x1..x2, z1..z2}. */
    static HandsOff within(MinecraftServer server, int x1, int z1, int x2, int z2) {
        List<int[]> home = new ArrayList<>();
        Territory territory = Territories.of(server);
        for (PartyId party : territory.parties()) {
            for (int[] row : Home.rows(overworld(territory.area(party)))) {
                add(home, x1, z1, x2, z2, row[0], row[1], row[2], row[3]);
            }
        }
        List<int[]> spots = new ArrayList<>();
        for (PlaceRow row : PlacesData.get(server).places().rows()) {
            Pos at = row.at();
            add(spots, x1, z1, x2, z2, at.x() - PLACE_MARGIN, at.z() - PLACE_MARGIN,
                    at.x() + PLACE_MARGIN, at.z() + PLACE_MARGIN);
        }
        int ring = FlattenPlan.Rules.MAX_RING;
        for (PartyBoard board : PartyBoards.all(server)) {
            for (Project project : board.projects()) {
                if (project instanceof Flatten flatten) {
                    add(spots, x1, z1, x2, z2, flatten.area().min().x() - ring,
                            flatten.area().min().z() - ring, flatten.area().max().x() + ring,
                            flatten.area().max().z() + ring);
                }
            }
        }
        for (List<Structure> structures : StructuresData.get(server).parties().values()) {
            for (Structure structure : structures) {
                Footprint pad = structure.pad();
                if (pad != null) {
                    add(spots, x1, z1, x2, z2, pad.minX() - Structure.RING, pad.minZ() - Structure.RING,
                            pad.maxX() + Structure.RING, pad.maxZ() + Structure.RING);
                }
            }
        }
        return SettledFence.of(home, spots);
    }

    /** The box, when it overlaps the window. */
    private static void add(List<int[]> boxes, int x1, int z1, int x2, int z2,
                            int bx1, int bz1, int bx2, int bz2) {
        if (bx2 >= x1 && bx1 <= x2 && bz2 >= z1 && bz1 <= z2) {
            boxes.add(new int[] {bx1, bz1, bx2, bz2});
        }
    }
}
