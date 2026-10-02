package dev.luizloyola.autarkia.mod.builder;

import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.PlaceFrom;
import dev.luizloyola.anima.core.nav.LaidBlocks;
import dev.luizloyola.anima.core.social.PartyId;
import dev.luizloyola.anima.mod.nav.LaidBlocksData;
import dev.luizloyola.autarkia.core.board.Deconstruct;
import dev.luizloyola.autarkia.core.bp.Footprint;
import dev.luizloyola.autarkia.core.builder.Structure;
import dev.luizloyola.autarkia.mod.board.PartyBoards;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * A building's scaffold taken down once it stands (builder spec, decision 8): every recorded pillar
 * block still standing round it — a builder called away before it came back down — goes to a
 * {@link Deconstruct}. A pillar a builder came back down is already gone.
 */
final class Scaffolds {

    /** Round the walls, as far as a pillar can stand and still reach them. */
    private static final int MARGIN = (int) Math.ceil(PlaceFrom.STAND_REACH) + 1;
    /** Over layer 0, as high as a pillar beside the highest building can rise. */
    private static final int HEIGHT = 24;
    /** As a build's (ruling 23). */
    private static final double PRIORITY = 0.5;

    private Scaffolds() {
    }

    /** @return what was done, for the members' journals; empty when nothing was left standing */
    static List<String> takeDown(MinecraftServer server, PartyId party, Structure built) {
        ServerLevel level = server.overworld();
        LaidBlocks laid = LaidBlocksData.get(server).laid();
        Footprint walls = built.built();
        int y0 = built.anchor().y() - 1;
        List<Deconstruct.Target> targets = new ArrayList<>();
        for (LaidBlocks.Row row : laid.rows()) {
            Pos pos = row.at();
            if (row.kind() != LaidBlocks.Kind.PILLAR || !party.equals(row.party())
                    || pos.x() < walls.minX() - MARGIN || pos.x() > walls.maxX() + MARGIN
                    || pos.z() < walls.minZ() - MARGIN || pos.z() > walls.maxZ() + MARGIN
                    || pos.y() < y0 || pos.y() > y0 + HEIGHT) {
                continue;
            }
            BlockPos at = new BlockPos(pos.x(), pos.y(), pos.z());
            String standing = BuiltInRegistries.BLOCK.getKey(level.getBlockState(at).getBlock()).toString();
            if (standing.equals(row.block())) {
                targets.add(new Deconstruct.Target(pos, row.block(), false));
            } else {
                laid.remove(pos); // broken already: the ledger catches up
            }
        }
        if (targets.isEmpty()) {
            return List.of();
        }
        PartyBoards.of(server, party).post(new Deconstruct(targets, "the scaffold of " + built.blueprint(), PRIORITY));
        PartyBoards.touch(server);
        return List.of("taking down the scaffold: " + targets.size() + (targets.size() == 1 ? " block" : " blocks"));
    }
}
