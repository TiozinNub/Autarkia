package dev.luizloyola.autarkia.mod.direction;

import dev.luizloyola.anima.core.brain.knowledge.Region;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.nav.HandsOff;
import dev.luizloyola.anima.core.social.PlaceRow;
import dev.luizloyola.anima.mod.social.PlacesData;
import dev.luizloyola.autarkia.core.direction.Home;
import dev.luizloyola.autarkia.core.direction.PartyProgress;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * The ground a party has made its own, which no walk lays a block on or cuts a block from
 * (docs/superpowers/specs/2026-09-28-bridging-design.md, safety rule 6): every party's HOME plot,
 * and a margin round every store and workbench. Structures, roads and fields join it when anything
 * records them.
 */
public final class SettledGround {

    /** How far past a route's ends a plot or a place is still asked about: a route bends. */
    private static final int REACH = 96;
    /** Columns kept whole round a store or a workbench, so no deck is laid against one. */
    private static final int PLACE_MARGIN = 1;

    private SettledGround() {
    }

    /** Anima's {@code WorkFence} rule. HOME and the places are the overworld's. */
    public static HandsOff around(ServerLevel level, BlockPos start, BlockPos goal) {
        MinecraftServer server = level.getServer();
        if (level != server.overworld()) {
            return HandsOff.NONE;
        }
        int x1 = Math.min(start.getX(), goal.getX()) - REACH;
        int z1 = Math.min(start.getZ(), goal.getZ()) - REACH;
        int x2 = Math.max(start.getX(), goal.getX()) + REACH;
        int z2 = Math.max(start.getZ(), goal.getZ()) + REACH;
        List<int[]> boxes = new ArrayList<>();
        for (PartyProgress party : DirectionsData.get(server).parties().values()) {
            Home home = party.home();
            if (home == null) {
                continue;
            }
            Region plot = home.plot();
            if (plot.max().x() >= x1 && plot.min().x() <= x2 && plot.max().z() >= z1
                    && plot.min().z() <= z2) {
                boxes.add(new int[] {plot.min().x(), plot.min().z(), plot.max().x(), plot.max().z()});
            }
        }
        for (PlaceRow row : PlacesData.get(server).places().rows()) {
            Pos at = row.at();
            if (at.x() >= x1 && at.x() <= x2 && at.z() >= z1 && at.z() <= z2) {
                boxes.add(new int[] {at.x() - PLACE_MARGIN, at.z() - PLACE_MARGIN,
                        at.x() + PLACE_MARGIN, at.z() + PLACE_MARGIN});
            }
        }
        return HandsOff.columns(boxes);
    }
}
