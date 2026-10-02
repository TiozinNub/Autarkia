package dev.luizloyola.autarkia.mod.direction;

import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.nav.HandsOff;
import dev.luizloyola.anima.core.social.PartyId;
import dev.luizloyola.anima.core.social.PlaceRow;
import dev.luizloyola.anima.core.territory.ChunkKey;
import dev.luizloyola.anima.core.territory.Territory;
import dev.luizloyola.anima.mod.social.PlacesData;
import dev.luizloyola.anima.mod.territory.Territories;
import dev.luizloyola.autarkia.core.direction.Home;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * The ground a party has made its own, which no walk lays a block on or cuts a block from
 * (docs/superpowers/specs/2026-09-28-bridging-design.md, safety rule 6): every party's area, and a
 * margin round every store and workbench. Structures, roads and fields join it when anything
 * records them.
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
        return HandsOff.columns(boxes(level, start, goal));
    }

    /** {@link #around}'s boxes, each {@code {x1, z1, x2, z2}}, for a caller that adds its own. */
    static List<int[]> boxes(ServerLevel level, BlockPos start, BlockPos goal) {
        MinecraftServer server = level.getServer();
        if (level != server.overworld()) {
            return new ArrayList<>();
        }
        int x1 = Math.min(start.getX(), goal.getX()) - REACH;
        int z1 = Math.min(start.getZ(), goal.getZ()) - REACH;
        int x2 = Math.max(start.getX(), goal.getX()) + REACH;
        int z2 = Math.max(start.getZ(), goal.getZ()) + REACH;
        List<int[]> boxes = new ArrayList<>();
        Territory territory = Territories.of(server);
        for (PartyId party : territory.parties()) {
            for (int[] row : Home.rows(overworld(territory.area(party)))) {
                if (row[2] >= x1 && row[0] <= x2 && row[3] >= z1 && row[1] <= z2) {
                    boxes.add(row);
                }
            }
        }
        for (PlaceRow row : PlacesData.get(server).places().rows()) {
            Pos at = row.at();
            if (at.x() >= x1 && at.x() <= x2 && at.z() >= z1 && at.z() <= z2) {
                boxes.add(new int[] {at.x() - PLACE_MARGIN, at.z() - PLACE_MARGIN,
                        at.x() + PLACE_MARGIN, at.z() + PLACE_MARGIN});
            }
        }
        return boxes;
    }
}
