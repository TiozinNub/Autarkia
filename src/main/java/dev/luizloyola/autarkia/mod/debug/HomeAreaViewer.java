package dev.luizloyola.autarkia.mod.debug;

import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.social.PartyId;
import dev.luizloyola.anima.core.territory.ChunkKey;
import dev.luizloyola.anima.mod.debug.CellOverlays;
import dev.luizloyola.anima.mod.net.CellOverlayPayload;
import dev.luizloyola.anima.mod.territory.TerritoryViewer;
import dev.luizloyola.autarkia.core.direction.Home;
import dev.luizloyola.autarkia.core.direction.PartyProgress;
import dev.luizloyola.autarkia.core.direction.PartyView;
import dev.luizloyola.autarkia.mod.direction.Directions;
import dev.luizloyola.autarkia.mod.direction.DirectionsData;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * What HOME adds to Anima's territory view, drawn whenever that view is on: where each party's next store goes, a
 * ring inside every chunk not yet cleared, and a label naming the HOME.
 */
public final class HomeAreaViewer {

    private HomeAreaViewer() {
    }

    private static final String SOURCE = "autarkia:home_area";
    private static final int REFRESH_TICKS = 20;
    private static final int TTL_TICKS = REFRESH_TICKS * 3;
    private static final int SPOT = 0xFF40FF40;
    private static final int UNCLEARED = 0xFFFFB020;
    private static final int LABEL = 0xFFFFFFFF;

    /** Who was shown a frame, so turning the territory view off clears this one too. */
    private static final Set<UUID> SHOWN = new HashSet<>();

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % REFRESH_TICKS != 0) {
                return;
            }
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (TerritoryViewer.watching(server, player)) {
                    render(server, player);
                    SHOWN.add(player.getUUID());
                } else if (SHOWN.remove(player.getUUID())) {
                    CellOverlays.clear(player, SOURCE);
                }
            }
        });
    }

    private static void render(MinecraftServer server, ServerPlayer player) {
        ServerLevel level = server.overworld();
        if (player.level() != level) {
            CellOverlays.clear(player, SOURCE);
            return;
        }
        int reach = TerritoryViewer.RANGE_BLOCKS;
        List<CellOverlayPayload.Box> spots = new ArrayList<>();
        List<CellOverlayPayload.Box> uncleared = new ArrayList<>();
        List<CellOverlayPayload.Label> labels = new ArrayList<>();
        for (Map.Entry<PartyId, PartyProgress> entry : DirectionsData.get(server).parties().entrySet()) {
            Home home = entry.getValue().home();
            if (home == null) {
                continue;
            }
            PartyView view = Directions.view(server, entry.getKey(), entry.getValue());
            Optional<Pos> next = view.spot();
            if (next.isEmpty() || Math.abs(next.get().x() - player.getBlockX()) > reach
                    || Math.abs(next.get().z() - player.getBlockZ()) > reach) {
                continue;
            }
            SortedSet<ChunkKey> area = view.area();
            List<ChunkKey> left = home.uncleared(area);
            BlockPos spot = new BlockPos(next.get().x(), next.get().y(), next.get().z());
            spots.add(new CellOverlayPayload.Box(spot, spot));
            for (ChunkKey chunk : left) {
                int y = groundAt(level, chunk, spot.getY() - 1);
                uncleared.add(new CellOverlayPayload.Box(
                        new BlockPos(chunk.minBlockX() + 2, y, chunk.minBlockZ() + 2),
                        new BlockPos(chunk.maxBlockX() - 2, y, chunk.maxBlockZ() - 2)));
            }
            labels.add(new CellOverlayPayload.Label("HOME — " + area.size() + " chunks, "
                    + (area.size() - left.size()) + " cleared", LABEL, spot.above(2)));
        }
        CellOverlays.show(player, new CellOverlayPayload(SOURCE, TTL_TICKS, List.of(), List.of(), List.of(
                new CellOverlayPayload.BoxGroup(SPOT, 3.0F, 0x6040FF40, true, spots),
                new CellOverlayPayload.BoxGroup(UNCLEARED, 2.0F, 0, false, uncleared)), labels));
    }

    /** The ground at the chunk's middle, without loading a chunk to ask. */
    private static int groundAt(ServerLevel level, ChunkKey chunk, int fallback) {
        if (!level.hasChunk(chunk.x(), chunk.z())) {
            return fallback;
        }
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, chunk.minBlockX() + 8,
                chunk.minBlockZ() + 8) - 1;
    }
}
