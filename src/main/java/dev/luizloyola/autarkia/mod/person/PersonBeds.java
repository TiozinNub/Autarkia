package dev.luizloyola.autarkia.mod.person;

import dev.luizloyola.anima.compat.agent.Sleeping;
import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.social.PartyId;
import dev.luizloyola.anima.mod.body.AgentBody;
import dev.luizloyola.anima.mod.social.PartyData;
import dev.luizloyola.autarkia.core.bp.Footprint;
import dev.luizloyola.autarkia.core.builder.Structure;
import dev.luizloyola.autarkia.mod.builder.StructuresData;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

/**
 * Which beds a Person may hold (sleep spec): never a bed a player sleeps in, nor one in another
 * party's building. Anima asks it before a claim and again whenever the bed is used.
 */
public final class PersonBeds {

    private PersonBeds() {
    }

    public static boolean mayHold(ServerLevel level, BlockPos head, AgentBody body) {
        MinecraftServer server = level.getServer();
        BlockPos foot = Sleeping.foot(level, head);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ServerPlayer.RespawnConfig respawn = player.getRespawnConfig();
            if (respawn != null && respawn.respawnData().dimension().equals(Level.OVERWORLD)
                    && (respawn.respawnData().pos().equals(head) || respawn.respawnData().pos().equals(foot))) {
                return false;
            }
        }
        AgentId id = body.agentId();
        Optional<PartyId> mine = id == null ? Optional.empty() : PartyData.get(server).currentPartyOf(id);
        for (Map.Entry<PartyId, List<Structure>> party : StructuresData.get(server).parties().entrySet()) {
            if (mine.filter(party.getKey()::equals).isPresent()) {
                continue;
            }
            for (Structure structure : party.getValue()) {
                if (structure.stands() && inside(structure.built(), head)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean inside(Footprint built, BlockPos at) {
        return built != null && at.getX() >= built.minX() && at.getX() <= built.maxX()
                && at.getZ() >= built.minZ() && at.getZ() <= built.maxZ();
    }
}
