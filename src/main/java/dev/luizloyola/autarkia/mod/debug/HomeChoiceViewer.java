package dev.luizloyola.autarkia.mod.debug;

import dev.luizloyola.anima.mod.debug.CellOverlays;
import dev.luizloyola.anima.mod.net.CellOverlayPayload;
import dev.luizloyola.autarkia.core.direction.HomeJudge.Candidate;
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
 * {@code home choose}: the best plots outlined where they lie, each labelled with its rank and
 * worth, for a minute. The best one is drawn apart. The frame is sent again while it lasts, as the
 * client lets a frame lapse.
 */
public final class HomeChoiceViewer {

    private static final String OVERLAY = "autarkia:home_choice";
    private static final int SHOWN_TICKS = 1200;
    private static final int REFRESH_TICKS = 10;
    private static final int TTL_TICKS = 30;
    private static final int BEST = 0xFF40FF60;
    private static final int OTHERS = 0xFFFF40FF;
    private static final int LABEL = 0xFFFFFFFF;
    private static final float WIDTH = 2.5F;

    private record View(CellOverlayPayload frame, long until) {
    }

    private static final Map<UUID, View> VIEWS = new ConcurrentHashMap<>();

    private HomeChoiceViewer() {
    }

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(HomeChoiceViewer::tick);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> VIEWS.clear());
    }

    /** Paints {@code shown}, best first, for {@code player}. */
    public static void show(ServerPlayer player, List<Candidate> shown) {
        List<CellOverlayPayload.Box> best = new ArrayList<>();
        List<CellOverlayPayload.Box> others = new ArrayList<>();
        List<CellOverlayPayload.Label> labels = new ArrayList<>();
        for (int i = 0; i < shown.size(); i++) {
            Candidate plot = shown.get(i);
            int h = plot.size() / 2;
            (i == 0 ? best : others).add(new CellOverlayPayload.Box(
                    new BlockPos(plot.x() - h, plot.y(), plot.z() - h),
                    new BlockPos(plot.x() + h, plot.y(), plot.z() + h)));
            // Numbers only; the words are in the command's reply.
            labels.add(new CellOverlayPayload.Label("#" + (i + 1) + "  " + Math.round(plot.value()),
                    LABEL, new BlockPos(plot.x(), plot.y() + 3, plot.z())));
        }
        List<CellOverlayPayload.BoxGroup> groups = new ArrayList<>();
        if (!best.isEmpty()) {
            groups.add(new CellOverlayPayload.BoxGroup(BEST, WIDTH, 0, true, best));
        }
        if (!others.isEmpty()) {
            groups.add(new CellOverlayPayload.BoxGroup(OTHERS, WIDTH, 0, true, others));
        }
        CellOverlayPayload frame = new CellOverlayPayload(OVERLAY, TTL_TICKS, List.of(), List.of(),
                groups, labels);
        VIEWS.put(player.getUUID(), new View(frame, player.level().getGameTime() + SHOWN_TICKS));
        CellOverlays.show(player, frame);
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
