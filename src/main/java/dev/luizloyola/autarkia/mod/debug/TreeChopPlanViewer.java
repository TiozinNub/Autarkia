package dev.luizloyola.autarkia.mod.debug;

import dev.luizloyola.anima.compat.sense.LevelProbe;
import dev.luizloyola.anima.core.brain.knowledge.BlockKind;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.mod.debug.CellOverlays;
import dev.luizloyola.anima.mod.net.CellOverlayPayload;
import dev.luizloyola.autarkia.core.tree.ChopPlan;
import dev.luizloyola.autarkia.core.tree.SplitReport;
import dev.luizloyola.autarkia.core.tree.TreeMasses;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;

/**
 * The choreographer's monocle, twin to {@link TreeSplitViewer}: every tree around the watching
 * player wears its {@link ChopPlan}, painted over the live world before any executor exists.
 *
 * <p>MAST gold, its entry cell white-rimmed and its planned extension above the trunk top a thin
 * gold climb; each chop on the plan's clock, green first through amber to red last, its access
 * DIGS the same hue washed out and its stand cell a faint white shell — orange for the one allowed
 * leap, gold where she places a log of her own underfoot; REFUSALS magenta. The tally over each
 * mast sums the card: chops (mast included), digs, refusals.
 *
 * <p>Leaves are unpainted: the plan never chops canopy except as access, and an
 * unpainted crown is the claim that decay will clear it.
 */
public final class TreeChopPlanViewer {
    private TreeChopPlanViewer() {}

    private static final String SOURCE = "autarkia:tree_plan";

    private static final int RESCAN_INTERVAL_TICKS = 40;
    private static final int TTL_TICKS = RESCAN_INTERVAL_TICKS * 3;
    public static final int DEFAULT_RADIUS = 24;

    private static final float BASE_STROKE_WIDTH = 2.5F;
    private static final float STROKE_WIDTH = 1.5F;
    private static final float THIN_WIDTH = 1.0F;

    /** Green through amber to red: where in the dance this swing falls. */
    private static final float FIRST_HUE = 0.33F;

    private static final int WHITE = 0xFFFFFFFF;
    private static final int MAST_STROKE = 0xFFFFD700;
    private static final int MAST_FILL = 0x3CFFD700;
    private static final int STAND_STROKE = 0x50FFFFFF;
    private static final int LEAP_STAND_STROKE = 0xFFFF9020;
    private static final int REFUSED_STROKE = 0xFFFF3FD4;
    private static final int REFUSED_FILL = 0x50FF3FD4;

    private static final Map<MinecraftServer, Map<UUID, Integer>> WATCHERS = new HashMap<>();

    /** Call once from mod init. */
    public static void init() {
        ServerLifecycleEvents.SERVER_STOPPING.register(WATCHERS::remove);
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % RESCAN_INTERVAL_TICKS != 0) {
                return;
            }
            Map<UUID, Integer> watches = WATCHERS.get(server);
            if (watches == null) {
                return;
            }
            Iterator<Map.Entry<UUID, Integer>> each = watches.entrySet().iterator();
            while (each.hasNext()) {
                Map.Entry<UUID, Integer> entry = each.next();
                ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
                if (player == null) {
                    each.remove();
                } else {
                    render(player, entry.getValue());
                }
            }
        });
    }

    /**
     * Toggles the plan view for this player, or retunes its radius while it is on.
     * Returns the radius now active, or {@code 0} when the toggle switched it off.
     */
    public static int toggle(MinecraftServer server, ServerPlayer player, int requestedRadius) {
        Map<UUID, Integer> watches = WATCHERS.computeIfAbsent(server, s -> new HashMap<>());
        Integer existing = watches.get(player.getUUID());
        if (existing != null && requestedRadius <= 0) {
            watches.remove(player.getUUID());
            CellOverlays.clear(player, SOURCE);
            return 0;
        }
        int radius = requestedRadius > 0 ? requestedRadius : DEFAULT_RADIUS;
        watches.put(player.getUUID(), radius);
        render(player, radius);
        return radius;
    }

    /** One frame: scan, individuate, compile every tree's dance card, paint, push. */
    private static void render(ServerPlayer player, int radius) {
        Level level = player.level();
        LevelProbe probe = new LevelProbe(player);
        BlockPos centre = player.blockPosition();
        int minY = Math.max(level.getMinY(), centre.getY() - radius);
        int maxY = Math.min(level.getMaxY(), centre.getY() + radius);

        Map<Pos, BlockKind> wood = new LinkedHashMap<>();
        for (int x = centre.getX() - radius; x <= centre.getX() + radius; x++) {
            for (int z = centre.getZ() - radius; z <= centre.getZ() + radius; z++) {
                for (int y = minY; y <= maxY; y++) {
                    BlockKind kind = probe.at(x, y, z);
                    if (kind == BlockKind.LOG || kind == BlockKind.LEAVES) {
                        wood.put(new Pos(x, y, z), kind);
                    }
                }
            }
        }

        List<CellOverlayPayload.Group> groups = new ArrayList<>();
        List<CellOverlayPayload.Label> labels = new ArrayList<>();
        for (Map<Pos, BlockKind> mass : TreeMasses.connect(wood)) {
            SplitReport report = SplitReport.of(mass, probe);
            for (var tree : report.trees()) {
                paint(ChopPlan.of(tree), groups, labels);
            }
        }
        if (groups.isEmpty() && labels.isEmpty()) {
            CellOverlays.clear(player, SOURCE);
            return;
        }
        CellOverlays.show(player,
                new CellOverlayPayload(SOURCE, TTL_TICKS, groups, List.of(), List.of(), labels));
    }

    /** One tree's card: gold mast, clock-hued moves, washed-out digs, painted refusals. */
    private static void paint(ChopPlan plan, List<CellOverlayPayload.Group> groups,
                              List<CellOverlayPayload.Label> labels) {
        groups.add(new CellOverlayPayload.Group(WHITE, BASE_STROKE_WIDTH, MAST_FILL, true,
                List.of(blockPos(plan.entry()))));
        if (plan.mast().size() > 1) {
            List<BlockPos> shaft = new ArrayList<>();
            for (Pos cell : plan.mast()) {
                if (!cell.equals(plan.entry())) {
                    shaft.add(blockPos(cell));
                }
            }
            // Climbing the trunk also takes the two headroom cells above the last rung — wood
            // coming down, so paint them or the shaft reads two short.
            if (plan.climbsTheTrunk()) {
                Pos last = plan.mast().get(plan.mast().size() - 1);
                shaft.add(new BlockPos(last.x(), last.y() + 1, last.z()));
                shaft.add(new BlockPos(last.x(), last.y() + 2, last.z()));
            }
            groups.add(new CellOverlayPayload.Group(
                    MAST_STROKE, STROKE_WIDTH, MAST_FILL, true, shaft));
        }

        int chops = plan.chopCount();
        List<BlockPos> stands = new ArrayList<>();
        List<BlockPos> leapStands = new ArrayList<>();
        List<BlockPos> boostStands = new ArrayList<>();
        int move = 0;
        for (ChopPlan.Layer layer : plan.layers()) {
            for (ChopPlan.Move m : layer.moves()) {
                float hue = chops <= 1 ? FIRST_HUE
                        : FIRST_HUE * (1 - move / (float) (chops - 1));
                groups.add(new CellOverlayPayload.Group(
                        Mth.hsvToArgb(hue, 0.90F, 1.0F, 0xC8), STROKE_WIDTH,
                        Mth.hsvToArgb(hue, 0.90F, 1.0F, 0x32), true,
                        List.of(blockPos(m.target()))));
                if (!m.digs().isEmpty()) {
                    groups.add(new CellOverlayPayload.Group(
                            Mth.hsvToArgb(hue, 0.25F, 1.0F, 0x50), THIN_WIDTH, 0, true,
                            blockPositions(m.digs())));
                }
                (m.boost() ? boostStands : m.leap() ? leapStands : stands)
                        .add(blockPos(m.stand()));
                if (move % 10 == 0 && chops > 1) {
                    labels.add(new CellOverlayPayload.Label(String.valueOf(move + 1),
                            Mth.hsvToArgb(hue, 0.90F, 1.0F, 0xFF), blockPos(m.target())));
                }
                move++;
            }
        }
        if (!stands.isEmpty()) {
            groups.add(new CellOverlayPayload.Group(STAND_STROKE, THIN_WIDTH, 0, true, stands));
        }
        if (!leapStands.isEmpty()) {
            groups.add(new CellOverlayPayload.Group(
                    LEAP_STAND_STROKE, STROKE_WIDTH, 0, true, leapStands));
        }
        if (!boostStands.isEmpty()) {
            // Gold like the mast: this stand is placed wood — one of her own logs underfoot.
            groups.add(new CellOverlayPayload.Group(
                    MAST_STROKE, STROKE_WIDTH, 0, true, boostStands));
        }

        if (!plan.refusals().isEmpty()) {
            List<BlockPos> refused = new ArrayList<>();
            for (ChopPlan.Refusal refusal : plan.refusals()) {
                refused.add(blockPos(refusal.cell()));
            }
            groups.add(new CellOverlayPayload.Group(
                    REFUSED_STROKE, BASE_STROKE_WIDTH, REFUSED_FILL, true, refused));
        }

        // Layers above the broken trunk top imply the mast extending on her own logs.
        if (!plan.mast().isEmpty()) {
            Pos site = plan.mast().get(plan.mast().size() - 1);
            int topLayer = plan.layers().isEmpty() ? site.y() : plan.layers().get(0).y();
            if (topLayer - 1 > site.y()) {
                List<BlockPos> extension = new ArrayList<>();
                for (int y = site.y() + 1; y <= topLayer - 1; y++) {
                    extension.add(new BlockPos(site.x(), y, site.z()));
                }
                groups.add(new CellOverlayPayload.Group(
                        MAST_STROKE, THIN_WIDTH, 0, true, extension));
            }
        }

        // The tally rides the top of the climb — or the stump, when there is no climb.
        Pos top = plan.mast().isEmpty() ? plan.entry()
                : plan.mast().get(plan.mast().size() - 1);
        String tally = (chops + plan.mast().size() + plan.ascentChops())
                + " chops · " + plan.digCount() + " digs"
                + (plan.refusals().isEmpty() ? "" : " · " + plan.refusals().size() + " refused");
        labels.add(new CellOverlayPayload.Label(tally,
                plan.refusals().isEmpty() ? MAST_STROKE : REFUSED_STROKE,
                new BlockPos(top.x(), top.y() + 1, top.z())));
    }

    private static BlockPos blockPos(Pos cell) {
        return new BlockPos(cell.x(), cell.y(), cell.z());
    }

    private static List<BlockPos> blockPositions(Iterable<Pos> cells) {
        List<BlockPos> out = new ArrayList<>();
        for (Pos cell : cells) {
            out.add(blockPos(cell));
        }
        return out;
    }
}
