package dev.luizloyola.autarkia.mod.debug;

import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.mod.debug.CellOverlays;
import dev.luizloyola.anima.mod.net.CellOverlayPayload;
import dev.luizloyola.autarkia.core.bp.Footprint;
import dev.luizloyola.autarkia.core.builder.HouseSite.Choice;
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
 * {@code bp site}: the best sites where they lie, for a minute — each pad outlined at the height it
 * would be levelled to, the walls inside it, the doorstep, and a label with its rank and cost. The
 * best is drawn apart. Re-sent while it lasts, as the client lets a frame lapse.
 */
public final class HouseSiteViewer {

    private static final String OVERLAY = "autarkia:house_site";
    private static final int SHOWN_TICKS = 1200;
    private static final int REFRESH_TICKS = 10;
    private static final int TTL_TICKS = 30;
    private static final int BEST = 0xFF40FF60;
    private static final int OTHERS = 0xFFFF40FF;
    private static final int WALLS = 0xFFFFFFFF;
    private static final int DOOR = 0xFFFFD040;
    private static final int LABEL = 0xFFFFFFFF;

    private record View(CellOverlayPayload frame, long until) {
    }

    private static final Map<UUID, View> VIEWS = new ConcurrentHashMap<>();

    private HouseSiteViewer() {
    }

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(HouseSiteViewer::tick);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> VIEWS.clear());
    }

    /** Paints {@code shown}, best first, for {@code player}. */
    public static void show(ServerPlayer player, List<Choice> shown) {
        List<CellOverlayPayload.Box> best = new ArrayList<>();
        List<CellOverlayPayload.Box> others = new ArrayList<>();
        List<CellOverlayPayload.Box> walls = new ArrayList<>();
        List<CellOverlayPayload.Box> doors = new ArrayList<>();
        List<CellOverlayPayload.Label> labels = new ArrayList<>();
        for (int i = 0; i < shown.size(); i++) {
            Choice choice = shown.get(i);
            (i == 0 ? best : others).add(box(choice.pad(), choice.y()));
            walls.add(box(choice.built(), choice.y() + 1));
            Pos step = choice.doorstep();
            BlockPos door = new BlockPos(step.x(), choice.y() + 1, step.z());
            doors.add(new CellOverlayPayload.Box(door, door));
            Footprint f = choice.built();
            // Numbers only; the terms are in the command's reply.
            labels.add(new CellOverlayPayload.Label("#" + (i + 1) + "  " + Math.round(choice.cost()), LABEL,
                    new BlockPos((f.minX() + f.maxX()) / 2, choice.y() + 4, (f.minZ() + f.maxZ()) / 2)));
        }
        List<CellOverlayPayload.BoxGroup> groups = new ArrayList<>();
        if (!best.isEmpty()) {
            groups.add(new CellOverlayPayload.BoxGroup(BEST, 3.0F, 0, true, best));
        }
        if (!others.isEmpty()) {
            groups.add(new CellOverlayPayload.BoxGroup(OTHERS, 2.0F, 0, true, others));
        }
        if (!walls.isEmpty()) {
            groups.add(new CellOverlayPayload.BoxGroup(WALLS, 1.5F, 0, false, walls));
            groups.add(new CellOverlayPayload.BoxGroup(DOOR, 2.5F, 0x60FFD040, false, doors));
        }
        CellOverlayPayload frame = new CellOverlayPayload(OVERLAY, TTL_TICKS, List.of(), List.of(), groups, labels);
        VIEWS.put(player.getUUID(), new View(frame, player.level().getGameTime() + SHOWN_TICKS));
        CellOverlays.show(player, frame);
    }

    public static void clear(ServerPlayer player) {
        VIEWS.remove(player.getUUID());
        CellOverlays.clear(player, OVERLAY);
    }

    private static CellOverlayPayload.Box box(Footprint f, int y) {
        return new CellOverlayPayload.Box(new BlockPos(f.minX(), y, f.minZ()), new BlockPos(f.maxX(), y, f.maxZ()));
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
                clear(player);
            } else {
                CellOverlays.show(player, view.frame());
            }
        }
    }
}
