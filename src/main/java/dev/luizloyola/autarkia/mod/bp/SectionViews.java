package dev.luizloyola.autarkia.mod.bp;

import dev.luizloyola.anima.mod.debug.CellOverlays;
import dev.luizloyola.anima.mod.net.CellOverlayPayload;
import dev.luizloyola.autarkia.core.builder.Section;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code bp sections}: a blueprint's sections painted at a site, a colour each, for a minute. The
 * frame is kept per player and sent again while it lasts, as the client lets a frame lapse.
 */
public final class SectionViews {

    private static final String OVERLAY = "autarkia:bp_sections";
    private static final int SHOWN_TICKS = 1200;
    private static final int REFRESH_TICKS = 10;
    private static final int TTL_TICKS = 30;
    private static final float STROKE_WIDTH = 2.0F;
    /** Fill is the stroke's colour at a quarter of its strength. */
    private static final int FILL_ALPHA = 0x40000000;

    private record View(CellOverlayPayload frame, long until) {
    }

    private static final Map<UUID, View> VIEWS = new ConcurrentHashMap<>();

    private SectionViews() {
    }

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(SectionViews::tick);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> VIEWS.clear());
    }

    /** The colour a section is painted and named in, opaque. */
    public static int colour(Section section) {
        return switch (section) {
            case FLOOR -> 0xFFB07840;
            case WALLS -> 0xFFE8E8E8;
            case LIGHTS -> 0xFFFFE040;
            case CEILING -> 0xFFE04848;
            case DOORS -> 0xFF4890FF;
            case EXTERIOR -> 0xFF48D048;
            case INTERIOR -> 0xFFC070FF;
        };
    }

    /**
     * Paints {@code cells} for {@code player}. With every section shown, only the faces in sight are
     * painted; with a few, they show through what stands in front of them.
     */
    public static void show(ServerPlayer player, Map<Section, List<BlockPos>> cells, boolean throughWalls) {
        List<CellOverlayPayload.Group> groups = new ArrayList<>();
        cells.forEach((section, at) -> groups.add(new CellOverlayPayload.Group(colour(section), STROKE_WIDTH,
                colour(section) & 0x00FFFFFF | FILL_ALPHA, throughWalls, at)));
        CellOverlayPayload frame = new CellOverlayPayload(OVERLAY, TTL_TICKS, groups, List.of(), List.of(), List.of());
        VIEWS.put(player.getUUID(), new View(frame, player.level().getGameTime() + SHOWN_TICKS));
        CellOverlays.show(player, frame);
    }

    /** Clears every painted view; returns how many there were. */
    public static int clear(MinecraftServer server) {
        int shown = VIEWS.size();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (VIEWS.remove(player.getUUID()) != null) {
                CellOverlays.clear(player, OVERLAY);
            }
        }
        VIEWS.clear();
        return shown;
    }

    private static void tick(MinecraftServer server) {
        if (VIEWS.isEmpty() || server.getTickCount() % REFRESH_TICKS != 0) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            View view = VIEWS.get(player.getUUID());
            if (view == null) {
                continue;
            }
            if (player.level().getGameTime() > view.until()) {
                VIEWS.remove(player.getUUID());
                CellOverlays.clear(player, OVERLAY);
            } else {
                CellOverlays.show(player, view.frame());
            }
        }
    }
}
