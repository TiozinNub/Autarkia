package dev.luizloyola.autarkia.mod.debug;

import dev.luizloyola.anima.compat.sense.LevelProbe;
import dev.luizloyola.anima.core.brain.knowledge.BlockKind;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.mod.debug.CellOverlays;
import dev.luizloyola.anima.mod.net.CellOverlayPayload;
import dev.luizloyola.autarkia.core.tree.SplitReport;
import dev.luizloyola.autarkia.core.tree.TreeMasses;
import dev.luizloyola.autarkia.core.tree.TreeSeams;
import dev.luizloyola.autarkia.core.tree.TreeShape;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;

/**
 * The surveyor's monocle: how the ground around the WATCHING PLAYER would carve into individual
 * trees, drawn as coloured gizmo boxes over the live world. No Person, no memories, no crescent
 * sampling — a direct box scan through the same {@link LevelProbe} vocabulary, reconnected into
 * masses ({@link TreeMasses}) and split by {@link TreeShape} exactly as the pipeline would; and it
 * follows the player.
 *
 * <p>Hue is OWNERSHIP, hashed from the anchor so it survives rescans — a boundary that flickers
 * hue as you move is itself a finding. Shade is ROLE: base cells white-rimmed and bright, the
 * column full-strength, branches lighter, leaves desaturated outlines. REFUSALS are painted, not
 * hidden: foreign-trunk logs magenta, ungrounded masses red, probe-dismissed leaves grey.
 *
 * <p>Transport is Anima's cell overlay ({@link CellOverlays}); this class owns the watcher map and
 * the rescan cadence: one map per server, gone on stop.
 */
public final class TreeSplitViewer {
    private TreeSplitViewer() {}

    private static final String SOURCE = "autarkia:tree_split";

    /** Rescan cadence — slow enough that the double scan (probe + masses) never matters. */
    private static final int RESCAN_INTERVAL_TICKS = 40;

    /** The client keeps drawing this long past the last frame — outlives one missed rescan. */
    private static final int TTL_TICKS = RESCAN_INTERVAL_TICKS * 3;

    public static final int DEFAULT_RADIUS = 24;

    /**
     * Cells per frame before the canopy thins. Solid wood is never dropped, only leaves — a big
     * scan's leaves outnumber everything and read fine sampled. Thinning is announced in chat.
     */
    private static final int MAX_CELLS = 8000;

    private static final float BASE_STROKE_WIDTH = 2.5F;
    private static final float STROKE_WIDTH = 1.5F;
    private static final float THIN_WIDTH = 1.0F;

    private static final int WHITE = 0xFFFFFFFF;
    private static final int STRAY_LOG_STROKE = 0xFFFF3FD4;
    private static final int STRAY_LOG_FILL = 0x50FF3FD4;
    private static final int STRAY_LEAF_STROKE = 0x8CFF3FD4;
    private static final int TREELESS_STROKE = 0xFFFF4040;
    private static final int TREELESS_FILL = 0x2DFF4040;
    private static final int DISMISSED_STROKE = 0x5AB4B4B4;

    /** One player's survey: the radius they asked for, and the last announced thinning step. */
    private static final class Watch {
        int radius;
        int lastThin = 1;

        Watch(int radius) {
            this.radius = radius;
        }
    }

    private static final Map<MinecraftServer, Map<UUID, Watch>> WATCHERS = new HashMap<>();

    /** Call once from mod init. */
    public static void init() {
        ServerLifecycleEvents.SERVER_STOPPING.register(WATCHERS::remove);
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % RESCAN_INTERVAL_TICKS != 0) {
                return;
            }
            Map<UUID, Watch> watches = WATCHERS.get(server);
            if (watches == null) {
                return;
            }
            Iterator<Map.Entry<UUID, Watch>> each = watches.entrySet().iterator();
            while (each.hasNext()) {
                Map.Entry<UUID, Watch> entry = each.next();
                ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
                if (player == null) {
                    each.remove(); // logged off: the survey dies with them, the TTL fades it
                } else {
                    render(player, entry.getValue());
                }
            }
        });
    }

    /**
     * Toggles the survey for this player, or retunes its radius while it is on.
     * Returns the radius now active, or {@code 0} when the toggle switched it off.
     */
    public static int toggle(MinecraftServer server, ServerPlayer player, int requestedRadius) {
        Map<UUID, Watch> watches = WATCHERS.computeIfAbsent(server, s -> new HashMap<>());
        Watch existing = watches.get(player.getUUID());
        if (existing != null && requestedRadius <= 0) {
            watches.remove(player.getUUID());
            CellOverlays.clear(player, SOURCE);
            return 0;
        }
        if (existing != null) {
            existing.radius = requestedRadius;
            render(player, existing);
            return existing.radius;
        }
        Watch fresh = new Watch(requestedRadius > 0 ? requestedRadius : DEFAULT_RADIUS);
        watches.put(player.getUUID(), fresh);
        render(player, fresh); // the first frame lands with the reply, not a cadence later
        return fresh.radius;
    }

    /**
     * Follows wood standing on the box's lid or floor out through it, so a tree taller than the
     * survey radius is drawn whole — without it the monocle individuates a clipped crown: a giant
     * called ungrounded, or a seam that exists only because the lid cut the canopy. Since
     * {@code TreeRule.standsTall} height is not measured at all.
     *
     * <p>Horizontally it stays inside the box. The cost is the tail alone: the flood starts on the
     * two planes and dies at the first cell that is not wood.
     */
    private static void followWoodPastTheLid(LevelProbe probe, Map<Pos, BlockKind> wood,
            Level level, BlockPos centre, int r, int minY, int maxY) {
        Deque<Pos> frontier = new ArrayDeque<>();
        for (Pos cell : wood.keySet()) {
            if (cell.y() == minY || cell.y() == maxY) {
                frontier.add(cell);
            }
        }
        Set<Pos> looked = new HashSet<>();
        while (!frontier.isEmpty()) {
            Pos p = frontier.poll();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        int y = p.y() + dy;
                        if (y >= minY && y <= maxY) {
                            continue; // inside the lid: the box scan already has it
                        }
                        if (y < level.getMinY() || y > level.getMaxY()) {
                            continue;
                        }
                        int x = p.x() + dx;
                        int z = p.z() + dz;
                        if (Math.abs(x - centre.getX()) > r || Math.abs(z - centre.getZ()) > r) {
                            continue; // the survey's own footprint, unchanged
                        }
                        Pos n = new Pos(x, y, z);
                        if (!looked.add(n)) {
                            continue;
                        }
                        BlockKind kind = probe.at(x, y, z);
                        if (kind == BlockKind.LOG || kind == BlockKind.LEAVES) {
                            wood.put(n, kind);
                            frontier.add(n);
                        }
                    }
                }
            }
        }
    }

    /** One frame: scan the box around them, individuate, paint, push. */
    private static void render(ServerPlayer player, Watch watch) {
        Level level = player.level();
        LevelProbe probe = new LevelProbe(player);
        BlockPos centre = player.blockPosition();
        int r = watch.radius;
        int minY = Math.max(level.getMinY(), centre.getY() - r);
        int maxY = Math.min(level.getMaxY(), centre.getY() + r);

        Map<Pos, BlockKind> wood = new LinkedHashMap<>();
        List<BlockPos> dismissed = new ArrayList<>();
        for (int x = centre.getX() - r; x <= centre.getX() + r; x++) {
            for (int z = centre.getZ() - r; z <= centre.getZ() + r; z++) {
                for (int y = minY; y <= maxY; y++) {
                    BlockKind kind = probe.at(x, y, z);
                    if (kind == BlockKind.LOG || kind == BlockKind.LEAVES) {
                        wood.put(new Pos(x, y, z), kind);
                    } else if (kind == BlockKind.OTHER
                            && level.getBlockState(new BlockPos(x, y, z)).is(BlockTags.LEAVES)) {
                        // The probe just dismissed a leaf block (built, or dying) — worth painting.
                        dismissed.add(new BlockPos(x, y, z));
                    }
                }
            }
        }
        followWoodPastTheLid(probe, wood, level, centre, r, minY, maxY);

        List<CellOverlayPayload.Group> solid = new ArrayList<>();
        List<CellOverlayPayload.Group> airy = new ArrayList<>();
        List<CellOverlayPayload.Label> labels = new ArrayList<>();
        List<BlockPos> strayLogs = new ArrayList<>();
        List<BlockPos> strayLeaves = new ArrayList<>();
        // The membrane between trees, two-tone: each side of a boundary face is a slab in its
        // tree's hue, grouped by hue since a group is one paint. Never thinned and never
        // depth-hidden — the boundary lives INSIDE a fused canopy by nature.
        Map<Float, List<CellOverlayPayload.Face>> seams = new LinkedHashMap<>();
        for (Map<Pos, BlockKind> mass : TreeMasses.connect(wood)) {
            SplitReport report = SplitReport.of(mass, probe);
            if (report.treeless()) {
                solid.add(new CellOverlayPayload.Group(TREELESS_STROKE, STROKE_WIDTH,
                        TREELESS_FILL, true, blockPositions(mass.keySet())));
                continue;
            }
            for (TreeShape.Trunk tree : report.trees()) {
                paint(tree, solid, airy, labels);
            }
            for (Pos log : report.strayLogs()) {
                strayLogs.add(new BlockPos(log.x(), log.y(), log.z()));
            }
            for (Pos leaf : report.strayLeaves()) {
                strayLeaves.add(new BlockPos(leaf.x(), leaf.y(), leaf.z()));
            }
            for (TreeSeams.Contact contact : TreeSeams.contacts(report.trees())) {
                Pos mineAnchor = report.trees().get(contact.tree()).base().get(0);
                Pos theirsAnchor = report.trees().get(contact.otherTree()).base().get(0);
                seams.computeIfAbsent(hueOf(mineAnchor), h -> new ArrayList<>())
                        .add(face(contact.cell(), contact.other()));
                seams.computeIfAbsent(hueOf(theirsAnchor), h -> new ArrayList<>())
                        .add(face(contact.other(), contact.cell()));
            }
        }
        if (!strayLogs.isEmpty()) {
            solid.add(new CellOverlayPayload.Group(
                    STRAY_LOG_STROKE, BASE_STROKE_WIDTH, STRAY_LOG_FILL, true, strayLogs));
        }
        if (!strayLeaves.isEmpty()) {
            airy.add(new CellOverlayPayload.Group(
                    STRAY_LEAF_STROKE, THIN_WIDTH, 0, false, strayLeaves));
        }
        if (!dismissed.isEmpty()) {
            airy.add(new CellOverlayPayload.Group(
                    DISMISSED_STROKE, THIN_WIDTH, 0, false, dismissed));
        }
        List<CellOverlayPayload.FaceGroup> faces = new ArrayList<>();
        for (Map.Entry<Float, List<CellOverlayPayload.Face>> side : seams.entrySet()) {
            faces.add(new CellOverlayPayload.FaceGroup(
                    Mth.hsvToArgb(side.getKey(), 0.85F, 1.0F, 0xC8), THIN_WIDTH,
                    Mth.hsvToArgb(side.getKey(), 0.85F, 1.0F, 0x64), true, side.getValue()));
        }

        thin(player, watch, solid, airy);
        List<CellOverlayPayload.Group> groups = new ArrayList<>(solid);
        groups.addAll(airy);
        if (groups.isEmpty() && faces.isEmpty() && labels.isEmpty()) {
            CellOverlays.clear(player, SOURCE); // an empty plain should show nothing, not linger
            return;
        }
        CellOverlays.show(player,
                new CellOverlayPayload(SOURCE, TTL_TICKS, groups, faces, List.of(), labels));
    }

    /** One tree's paint: hue by anchor, shade by role, and its tally floating over the top. */
    private static void paint(TreeShape.Trunk tree, List<CellOverlayPayload.Group> solid,
                              List<CellOverlayPayload.Group> airy,
                              List<CellOverlayPayload.Label> labels) {
        float hue = hueOf(tree.base().get(0));
        // The wood draws through the world (a trunk is buried inside its own canopy) its fills
        // translucent so it does not blot out what stands behind. Leaves stay depth-tested: a
        // forest of x-rayed canopies is soup, and their shell reads fine.
        solid.add(new CellOverlayPayload.Group(WHITE, BASE_STROKE_WIDTH,
                Mth.hsvToArgb(hue, 0.85F, 1.0F, 0x50), true, blockPositions(tree.base())));
        if (!tree.column().isEmpty()) {
            solid.add(new CellOverlayPayload.Group(
                    Mth.hsvToArgb(hue, 0.85F, 1.0F, 0xC8), STROKE_WIDTH,
                    Mth.hsvToArgb(hue, 0.85F, 1.0F, 0x32), true, blockPositions(tree.column())));
        }
        if (!tree.branches().isEmpty()) {
            solid.add(new CellOverlayPayload.Group(
                    Mth.hsvToArgb(hue, 0.50F, 1.0F, 0xC8), STROKE_WIDTH,
                    Mth.hsvToArgb(hue, 0.50F, 1.0F, 0x28), true, blockPositions(tree.branches())));
        }
        if (!tree.leaves().isEmpty()) {
            airy.add(new CellOverlayPayload.Group(
                    Mth.hsvToArgb(hue, 0.35F, 1.0F, 0x6E), THIN_WIDTH, 0, false,
                    blockPositions(tree.leaves())));
        }
        // The tally rides the top of the COLUMN, not the canopy: it labels the trunk it counts,
        // and the column top is findable from outside where a canopy-top label floats mid-leaf.
        List<Pos> column = tree.column().isEmpty() ? tree.base() : tree.column();
        Pos top = column.get(column.size() - 1);
        String text = (tree.base().size() > 1 ? tree.base().size() + "-wide · " : "")
                + tree.logCount() + (tree.logCount() == 1 ? " log" : " logs");
        labels.add(new CellOverlayPayload.Label(text,
                Mth.hsvToArgb(hue, 0.85F, 1.0F, 0xFF),
                new BlockPos(top.x(), top.y() + 1, top.z())));
    }

    /**
     * Keeps the frame under {@link #MAX_CELLS} by sampling the airy groups (every k-th cell), and
     * says so in chat when the step changes: a thinned canopy looks exactly like a split that
     * missed leaves.
     */
    private static void thin(ServerPlayer player, Watch watch,
                             List<CellOverlayPayload.Group> solid,
                             List<CellOverlayPayload.Group> airy) {
        int solidCells = solid.stream().mapToInt(g -> g.cells().size()).sum();
        int airyCells = airy.stream().mapToInt(g -> g.cells().size()).sum();
        int budget = Math.max(1, MAX_CELLS - solidCells);
        int step = airyCells > budget ? (airyCells + budget - 1) / budget : 1;
        if (step > 1) {
            for (int i = 0; i < airy.size(); i++) {
                CellOverlayPayload.Group group = airy.get(i);
                List<BlockPos> kept = new ArrayList<>((group.cells().size() / step) + 1);
                for (int c = 0; c < group.cells().size(); c += step) {
                    kept.add(group.cells().get(c));
                }
                airy.set(i, new CellOverlayPayload.Group(
                        group.stroke(), group.strokeWidth(), group.fill(), group.onTop(), kept));
            }
        }
        if (step != watch.lastThin) {
            watch.lastThin = step;
            player.sendSystemMessage(Component.literal(step > 1
                            ? "Dense canopy — drawing every " + step + ". leaf ("
                                    + (solidCells + airyCells) + " cells in view)."
                            : "Canopy no longer thinned — every leaf drawn.")
                    .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
        }
    }

    /** The face of {@code cell} toward the adjacent {@code toward} — one side of a boundary. */
    private static CellOverlayPayload.Face face(Pos cell, Pos toward) {
        Direction side = toward.x() > cell.x() ? Direction.EAST
                : toward.x() < cell.x() ? Direction.WEST
                : toward.y() > cell.y() ? Direction.UP
                : toward.y() < cell.y() ? Direction.DOWN
                : toward.z() > cell.z() ? Direction.SOUTH
                : Direction.NORTH;
        return new CellOverlayPayload.Face(
                new BlockPos(cell.x(), cell.y(), cell.z()), side.get3DDataValue());
    }

    /** Anchor → hue, stable across rescans: a tree keeps its colour for as long as it stands. */
    private static float hueOf(Pos anchor) {
        int hash = anchor.x() * 73856093 ^ anchor.y() * 19349663 ^ anchor.z() * 83492791;
        return Math.floorMod(hash, 997) / 997F;
    }

    private static List<BlockPos> blockPositions(Iterable<Pos> cells) {
        List<BlockPos> out = new ArrayList<>();
        for (Pos cell : cells) {
            out.add(new BlockPos(cell.x(), cell.y(), cell.z()));
        }
        return out;
    }
}
