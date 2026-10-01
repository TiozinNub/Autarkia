package dev.luizloyola.autarkia.mod.debug;

import dev.luizloyola.anima.core.terrain.NaturalGround;
import dev.luizloyola.anima.mod.debug.CellOverlays;
import dev.luizloyola.anima.mod.net.CellOverlayPayload;
import dev.luizloyola.autarkia.core.earthwork.FlattenPlan;
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
 * {@code flatten plan}: every block the plan cuts in red, every block it fills in blue, the area
 * outlined and refused columns in yellow, for a minute. The frame is sent again while it lasts, as
 * the client lets a frame lapse.
 */
public final class FlattenPlanViewer {

    private static final String OVERLAY = "autarkia:flatten_plan";
    private static final int SHOWN_TICKS = 1200;
    private static final int REFRESH_TICKS = 10;
    private static final int TTL_TICKS = 30;
    private static final int CUT = 0xFFFF4040;
    private static final int FILL = 0xFF4080FF;
    private static final int REFUSED = 0xFFFFE040;
    private static final int AREA = 0xFFFFFFFF;
    private static final int SHADE = 0x40000000;
    private static final float WIDTH = 2.0F;
    /** Past this many cells only the goal surface is painted; a big cut would flood the packet. */
    private static final int MOST_CELLS = 20_000;

    private record View(CellOverlayPayload frame, long until) {
    }

    private static final Map<UUID, View> VIEWS = new ConcurrentHashMap<>();

    private FlattenPlanViewer() {
    }

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(FlattenPlanViewer::tick);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> VIEWS.clear());
    }

    /** Paints {@code plan} over {@code [min, max]} for {@code player}; refusals stand on the scan's ground. */
    public static void show(ServerPlayer player, FlattenPlan plan, NaturalGround scan, BlockPos min,
                            BlockPos max) {
        int total = plan.cut() + plan.fill();
        boolean surfaceOnly = total > MOST_CELLS;
        List<BlockPos> cut = new ArrayList<>();
        List<BlockPos> fill = new ArrayList<>();
        for (FlattenPlan.Column c : plan.columns()) {
            if (c.goal() < c.ground()) {
                for (int y = surfaceOnly ? c.goal() + 1 : c.ground(); y > c.goal(); y--) {
                    cut.add(new BlockPos(c.x(), y, c.z()));
                }
            } else {
                for (int y = surfaceOnly ? c.goal() : c.ground() + 1; y <= c.goal(); y++) {
                    fill.add(new BlockPos(c.x(), y, c.z()));
                }
            }
        }
        List<BlockPos> refused = new ArrayList<>();
        for (FlattenPlan.Refused r : plan.refusals()) {
            int y = scan.contains(r.x(), r.z()) ? scan.groundAt(r.x(), r.z()) : NaturalGround.UNKNOWN;
            refused.add(new BlockPos(r.x(), y == NaturalGround.UNKNOWN ? min.getY() : y, r.z()));
        }
        List<CellOverlayPayload.Group> groups = new ArrayList<>();
        if (!cut.isEmpty()) {
            groups.add(new CellOverlayPayload.Group(CUT, 1.0F, (CUT & 0x00FFFFFF) | SHADE, false, cut));
        }
        if (!fill.isEmpty()) {
            groups.add(new CellOverlayPayload.Group(FILL, 1.0F, (FILL & 0x00FFFFFF) | SHADE, false, fill));
        }
        if (!refused.isEmpty()) {
            groups.add(new CellOverlayPayload.Group(REFUSED, WIDTH, (REFUSED & 0x00FFFFFF) | SHADE, true,
                    refused));
        }
        List<CellOverlayPayload.BoxGroup> boxes = List.of(new CellOverlayPayload.BoxGroup(AREA, WIDTH, 0,
                true, List.of(new CellOverlayPayload.Box(min, max))));
        // Numbers only; the words are in the command's reply.
        List<CellOverlayPayload.Label> labels = plan.refused() ? List.of()
                : List.of(new CellOverlayPayload.Label("y " + plan.y() + "  -" + plan.cut() + " +" + plan.fill(),
                        AREA, new BlockPos((min.getX() + max.getX()) / 2, plan.y() + 3,
                                (min.getZ() + max.getZ()) / 2)));
        CellOverlayPayload frame = new CellOverlayPayload(OVERLAY, TTL_TICKS, groups, List.of(), boxes,
                labels);
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
