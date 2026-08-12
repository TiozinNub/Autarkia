package dev.luizloyola.autarkia.mod.debug;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.knowledge.Region;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.SurveyArea;
import dev.luizloyola.anima.mod.body.AgentBodies;
import dev.luizloyola.anima.mod.body.AgentBody;
import dev.luizloyola.anima.mod.debug.CellOverlays;
import dev.luizloyola.anima.mod.identity.AgentDirectory;
import dev.luizloyola.anima.mod.net.CellOverlayPayload;
import dev.luizloyola.autarkia.core.board.ClearArea;
import dev.luizloyola.autarkia.core.board.PartyBoard;
import dev.luizloyola.autarkia.core.board.Project;
import dev.luizloyola.autarkia.core.board.WorkKey;
import dev.luizloyola.autarkia.mod.board.PartyBoards;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * A party's clearing project, drawn over the world it is about — the ledger made visible. Layer 3
 * is the one layer with no body to look at: the first forest run's 69% refusal rate took a dozen
 * queries and a scout spawned to count what was still standing.
 *
 * <p><b>Hue is STATE:</b>
 *
 * <ul>
 *   <li><b>Slices</b> — grey unwalked, amber being walked, green reported, dashed-dark while
 *       sitting out a failure, since "nobody is on it" and "nobody may take it yet" differ.</li>
 *   <li><b>The live sweep</b> — the coverage grid of whoever is surveying now, cell by cell,
 *       shaded by confidence.</li>
 *   <li><b>Targets</b> — white pending, cyan held, orange cooling off, dim green cleared, red
 *       refused; refused is loudest, being the state that leaves work standing in the box.</li>
 * </ul>
 *
 * <p>Transport is Anima's generic cell overlay; this class owns the watcher map and the cadence —
 * transient debug state, one map per server, gone on stop.
 */
public final class BoardViewer {
    private BoardViewer() {}

    private static final String SOURCE = "autarkia:board";

    /** Redraw cadence. Slower than the tree monocle: a project changes on a project's timescale. */
    private static final int REDRAW_INTERVAL_TICKS = 20;

    /** The client keeps drawing this long past the last frame — outlives a missed redraw. */
    private static final int TTL_TICKS = REDRAW_INTERVAL_TICKS * 4;

    /** How far from the player a project's box may be and still be drawn. */
    private static final int RANGE = 512;

    /**
     * Shapes per frame. Far lower than a cell budget needs to be, because a region now costs one
     * shape instead of its perimeter: four slices, their coverage tiles and a ledger of a few
     * hundred trees all fit inside this with room to spare.
     */
    private static final int MAX_SHAPES = 4_000;

    private static final int BOUNDS = 0xFFFFFFFF;
    private static final float BOUNDS_WIDTH = 3.5F;
    private static final float SLICE_WIDTH = 2.0F;
    private static final float TARGET_WIDTH = 2.5F;

    // Slices: the coarse explored answer.
    private static final int SLICE_UNWALKED = 0xFF6E6E6E;
    private static final int SLICE_WALKING = 0xFFFFB020;
    private static final int SLICE_WALKED = 0xFF35C24A;
    private static final int SLICE_COOLING = 0xFF8A4B10;

    // Targets, by what the project has decided about them.
    private static final int TARGET_PENDING = 0xFFF2F2F2;
    private static final int TARGET_PENDING_FILL = 0x30F2F2F2;
    private static final int TARGET_HELD = 0xFF32C8FF;
    private static final int TARGET_HELD_FILL = 0x5032C8FF;
    private static final int TARGET_COOLING = 0xFFFF9020;
    private static final int TARGET_COOLING_FILL = 0x40FF9020;
    private static final int TARGET_CLEARED = 0x8032C24A;
    private static final int TARGET_REFUSED = 0xFFFF2020;
    private static final int TARGET_REFUSED_FILL = 0x60FF2020;

    /** Ground written off for later passes — clear, and nothing found anywhere near it. */
    private static final int SETTLED = 0x281060C0;

    // The live sweep's coverage, shaded by how well the cell is known.
    private static final int COVER_KNOWN = 0x5040E060;
    private static final int COVER_PARTIAL = 0x40C0C040;
    private static final int COVER_UNKNOWN = 0x30FF6060;

    private static final int LABEL_COLOR = 0xFFFFFFFF;

    private static final Map<MinecraftServer, Set<UUID>> WATCHERS = new HashMap<>();

    /** Call once from mod init. */
    public static void init() {
        ServerLifecycleEvents.SERVER_STOPPING.register(WATCHERS::remove);
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % REDRAW_INTERVAL_TICKS != 0) {
                return;
            }
            Set<UUID> watching = WATCHERS.get(server);
            if (watching == null || watching.isEmpty()) {
                return;
            }
            Iterator<UUID> each = watching.iterator();
            while (each.hasNext()) {
                ServerPlayer player = server.getPlayerList().getPlayer(each.next());
                if (player == null) {
                    each.remove(); // logged off: the TTL fades what they were watching
                } else {
                    render(server, player);
                }
            }
        });
    }

    public static boolean isWatching(MinecraftServer server, ServerPlayer player) {
        Set<UUID> watching = WATCHERS.get(server);
        return watching != null && watching.contains(player.getUUID());
    }

    /** Turns the view on or off for this player; returns whether it is now on. */
    public static boolean toggle(MinecraftServer server, ServerPlayer player, boolean on) {
        Set<UUID> watching = WATCHERS.computeIfAbsent(server, s -> new HashSet<>());
        if (!on) {
            watching.remove(player.getUUID());
            CellOverlays.clear(player, SOURCE);
            return false;
        }
        watching.add(player.getUUID());
        render(server, player); // the first frame lands with the reply, not a cadence later
        return true;
    }

    /** How many projects are currently in range of this player — what the reply reports. */
    public static int inRange(MinecraftServer server, ServerPlayer player) {
        return nearby(server, player).size();
    }

    private static List<ClearArea> nearby(MinecraftServer server, ServerPlayer player) {
        List<ClearArea> out = new ArrayList<>();
        Pos here = new Pos(player.blockPosition().getX(), player.blockPosition().getY(),
                player.blockPosition().getZ());
        for (PartyBoard board : PartyBoards.all(server)) {
            for (Project project : board.projects()) {
                if (project instanceof ClearArea area && withinRange(area.bounds(), here)) {
                    out.add(area);
                }
            }
        }
        return out;
    }

    /** Horizontal distance from the box, zero when inside it — a box you stand in is in range. */
    private static boolean withinRange(Region bounds, Pos here) {
        int dx = Math.max(0, Math.max(bounds.min().x() - here.x(), here.x() - bounds.max().x()));
        int dz = Math.max(0, Math.max(bounds.min().z() - here.z(), here.z() - bounds.max().z()));
        return dx <= RANGE && dz <= RANGE;
    }

    private static void render(MinecraftServer server, ServerPlayer player) {
        List<ClearArea> projects = nearby(server, player);
        if (projects.isEmpty()) {
            CellOverlays.clear(player, SOURCE);
            return;
        }
        ServerLevel level = player.level();
        long now = level.getGameTime();
        Frame frame = new Frame(level);
        for (ClearArea project : projects) {
            PartyBoard board = boardOf(server, project);
            Map<WorkKey, AgentId> holds =
                    board == null ? Map.of() : board.holdsOn(project, now);
            paintSlices(frame, project, holds, now);
            paintTargets(frame, project, holds, now);
            paintSkippable(frame, project);
            paintSweeps(frame, server, project);
            frame.groundOutline(project.bounds(), BOUNDS, BOUNDS_WIDTH);
            frame.label(project.describe(), centreOf(project.bounds()), 3);
        }
        CellOverlays.show(player, frame.build());
    }

    private static PartyBoard boardOf(MinecraftServer server, ClearArea project) {
        for (PartyBoard board : PartyBoards.all(server)) {
            if (board.projects().contains(project)) {
                return board;
            }
        }
        return null;
    }

    /** The coarse explored answer: one outline per slice, coloured by what the project knows. */
    private static void paintSlices(Frame frame, ClearArea project, Map<WorkKey, AgentId> holds,
                                    long now) {
        Set<Integer> reported = project.reported();
        List<Region> slices = project.slices();
        for (int index = 0; index < slices.size(); index++) {
            Region slice = slices.get(index);
            boolean held = holds.containsKey(new WorkKey(WorkKey.SURVEY, slice.min()));
            int colour = reported.contains(index) ? SLICE_WALKED
                    : held ? SLICE_WALKING
                    : project.sliceCoolingAt(index, now) ? SLICE_COOLING
                    : SLICE_UNWALKED;
            frame.groundOutline(slice, colour, SLICE_WIDTH);
            String state = reported.contains(index) ? "walked"
                    : held ? "being walked"
                    : project.sliceCoolingAt(index, now) ? "cooling off"
                    : "unwalked";
            frame.label("slice " + (index + 1) + "/" + slices.size() + " — " + state,
                    centreOf(slice), 3);
        }
    }

    /** Every ledger row, at its anchor, in the colour of what the project decided about it. */
    private static void paintTargets(Frame frame, ClearArea project, Map<WorkKey, AgentId> holds,
                                     long now) {
        for (ClearArea.Target target : project.ledger().values()) {
            Pos at = target.anchor();
            switch (target.state()) {
                case CLEARED -> frame.cell(at, TARGET_CLEARED, 0, TARGET_WIDTH);
                case REFUSED -> frame.cell(at, TARGET_REFUSED, TARGET_REFUSED_FILL, TARGET_WIDTH);
                case OPEN -> {
                    if (holds.containsKey(new WorkKey(WorkKey.CLEAR, at))) {
                        frame.cell(at, TARGET_HELD, TARGET_HELD_FILL, TARGET_WIDTH);
                    } else if (target.retryAfter() > now) {
                        frame.cell(at, TARGET_COOLING, TARGET_COOLING_FILL, TARGET_WIDTH);
                    } else {
                        frame.cell(at, TARGET_PENDING, TARGET_PENDING_FILL, TARGET_WIDTH);
                    }
                }
            }
        }
    }

    /**
     * The fine explored answer: the coverage grid of anybody sweeping a slice of this project,
     * shaded by confidence.
     *
     * <p>Coverage lives on the running task, not the project — one worker's progress through one
     * slice, discarded if their hold lapses — so it is read off whatever primitive their executor
     * is on, and is absent when nobody is surveying.
     */
    private static void paintSweeps(Frame frame, MinecraftServer server, ClearArea project) {
        for (AgentBody body : AgentBodies.loaded(server)) {
            body.brain().executor().currentPrimitive()
                    .filter(SurveyArea.class::isInstance)
                    .map(SurveyArea.class::cast)
                    .filter(sweep -> project.slices().stream()
                            .anyMatch(slice -> slice.min().equals(sweep.area().min())))
                    .ifPresent(sweep -> paintCoverage(frame, sweep));
        }
    }

    /**
     * Ground a verify pass will not walk again: clear last time, and clear all around it.
     *
     * <p>Worth drawing because it is the one part of the plan that is an ABSENCE — the surveyor
     * skipping it looks identical to the surveyor never getting to it. Empty until a first pass
     * has finished.
     */
    private static void paintSkippable(Frame frame, ClearArea project) {
        for (Pos corner : project.skippable()) {
            frame.groundPane(corner.x(), corner.z(),
                    corner.x() + SurveyArea.CELL - 1, corner.z() + SurveyArea.CELL - 1,
                    0, SETTLED, 0.0F);
        }
    }

    private static void paintCoverage(Frame frame, SurveyArea sweep) {
        Region area = sweep.area();
        for (int x = area.min().x(); x <= area.max().x(); x += SurveyArea.CELL) {
            for (int z = area.min().z(); z <= area.max().z(); z += SurveyArea.CELL) {
                double known = sweep.confidenceAt(new Pos(x, area.min().y(), z));
                int colour = known >= 1.0 ? COVER_KNOWN
                        : known >= SurveyArea.ENOUGH ? COVER_PARTIAL
                        : COVER_UNKNOWN;
                frame.groundPane(x, z, Math.min(x + SurveyArea.CELL - 1, area.max().x()),
                        Math.min(z + SurveyArea.CELL - 1, area.max().z()), 0, colour, 0.0F);
            }
        }
    }

    private static Pos centreOf(Region box) {
        return new Pos((box.min().x() + box.max().x()) / 2, box.min().y(),
                (box.min().z() + box.max().z()) / 2);
    }

    /**
     * One frame under construction.
     *
     * <p>Regions are BOXES, not chains of cells: a slice edge drawn cell by cell is 190 block
     * outlines that eat the frame budget, where one box says it once at any size. A box flattened
     * on an axis is a pane — how coverage tiles and the box's own footprint are drawn. Only the
     * targets stay cells, a tree anchor genuinely being one block.
     */
    private static final class Frame {
        private final ServerLevel level;
        private final Map<Long, List<BlockPos>> cellsByPaint = new HashMap<>();
        private final Map<Long, List<CellOverlayPayload.Box>> boxesByPaint = new HashMap<>();
        private final Map<Long, int[]> paints = new HashMap<>();
        private final List<CellOverlayPayload.Label> labels = new ArrayList<>();
        private int drawn;

        Frame(ServerLevel level) {
            this.level = level;
        }

        /** One block, for the things that really are one block. */
        void cell(Pos at, int stroke, int fill, float width) {
            if (spent()) {
                return;
            }
            long key = paint(stroke, fill, width);
            cellsByPaint.computeIfAbsent(key, k -> new ArrayList<>())
                    .add(new BlockPos(at.x(), at.y(), at.z()));
        }

        /** A whole region as one wireframe — the whole point of the box primitive. */
        void box(Region region, int stroke, int fill, float width) {
            if (spent()) {
                return;
            }
            long key = paint(stroke, fill, width);
            boxesByPaint.computeIfAbsent(key, k -> new ArrayList<>()).add(
                    new CellOverlayPayload.Box(
                            new BlockPos(region.min().x(), region.min().y(), region.min().z()),
                            new BlockPos(region.max().x(), region.max().y(), region.max().z())));
        }

        /**
         * A flat pane laid on the ground across a footprint — a coverage tile.
         *
         * <p>Height comes from the terrain at the middle of the patch: a pane is one quad, so it
         * gets one height, and the centre keeps it near the ground it describes rather than
         * hovering at the tallest tree in a corner.
         */
        void groundPane(int fromX, int fromZ, int toX, int toZ, int stroke, int fill, float width) {
            int y = groundAt((fromX + toX) / 2, (fromZ + toZ) / 2);
            box(new Region(new Pos(fromX, y, fromZ), new Pos(toX, y, toZ)), stroke, fill, width);
        }

        /** The box's own footprint, laid on the ground rather than floating at its floor. */
        void groundOutline(Region bounds, int stroke, float width) {
            int y = groundAt((bounds.min().x() + bounds.max().x()) / 2,
                    (bounds.min().z() + bounds.max().z()) / 2);
            box(new Region(new Pos(bounds.min().x(), y, bounds.min().z()),
                    new Pos(bounds.max().x(), y, bounds.max().z())), stroke, 0, width);
        }

        private int groundAt(int x, int z) {
            return level.getHeight(
                    net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x, z);
        }

        private boolean spent() {
            return drawn++ >= MAX_SHAPES;
        }

        private long paint(int stroke, int fill, float width) {
            long key = (long) stroke << 32 ^ (fill & 0xFFFFFFFFL) ^ (long) (width * 10) << 3;
            paints.putIfAbsent(key, new int[] {stroke, fill, (int) (width * 10)});
            return key;
        }

        void label(String text, Pos at, int above) {
            labels.add(new CellOverlayPayload.Label(text, LABEL_COLOR,
                    new BlockPos(at.x(), groundAt(at.x(), at.z()) + above, at.z())));
        }

        CellOverlayPayload build() {
            List<CellOverlayPayload.Group> groups = new ArrayList<>();
            cellsByPaint.forEach((key, cells) -> {
                int[] paint = paints.get(key);
                groups.add(new CellOverlayPayload.Group(paint[0], paint[2] / 10.0F, paint[1],
                        true, cells));
            });
            List<CellOverlayPayload.BoxGroup> boxes = new ArrayList<>();
            boxesByPaint.forEach((key, drawnBoxes) -> {
                int[] paint = paints.get(key);
                boxes.add(new CellOverlayPayload.BoxGroup(paint[0], paint[2] / 10.0F, paint[1],
                        true, drawnBoxes));
            });
            return new CellOverlayPayload(SOURCE, TTL_TICKS, groups, List.of(), boxes, labels);
        }
    }
}
