package dev.luizloyola.autarkia.mod.debug;

import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.mod.debug.CellOverlays;
import dev.luizloyola.anima.mod.net.CellOverlayPayload;
import dev.luizloyola.autarkia.core.tree.Approach;
import dev.luizloyola.autarkia.core.tree.Climb;
import dev.luizloyola.autarkia.core.tree.FellTree;
import dev.luizloyola.autarkia.mod.entity.Person;
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
import net.minecraft.server.level.ServerPlayer;

/**
 * How the fellers around the watching player read the ground beside their trees — every running
 * {@link FellTree}'s {@link Approach}, drawn as gizmo boxes: the base white-rimmed, each ring cell
 * in its verdict's colour, the feet cell filled where there is one, and the leaves in the way
 * outlined. A label per side says the verdict and its score, the side being taken marked and its
 * feet cell white-rimmed; one over the stump says what the body is doing about it. Once there is
 * a {@link Climb}, the logs it opens are orange, the cell it stands in white, the logs above it
 * amber, the blocks it places on the way up blue, and the wood it takes on the way down and from
 * outside a paler orange.
 *
 * <p>Transport is Anima's cell overlay ({@link CellOverlays}). Same shape as {@link BoardViewer}:
 * a watcher set per server, a redraw cadence, gone on stop.
 */
public final class FellViewer {
    private FellViewer() {}

    private static final String SOURCE = "autarkia:fell";

    /** Half the task's own re-read cadence, so a change shows the frame after it is read. */
    private static final int REDRAW_INTERVAL_TICKS = 10;

    /** The client keeps drawing this long past the last frame — outlives one missed redraw. */
    private static final int TTL_TICKS = REDRAW_INTERVAL_TICKS * 3;

    /** How far from the player a feller is still drawn. */
    private static final int RANGE = 64;

    private static final float BASE_WIDTH = 2.5F;
    private static final float WIDTH = 1.5F;
    private static final float THIN = 1.0F;

    private static final int WHITE = 0xFFFFFFFF;
    private static final int BASE_FILL = 0x40FFFFFF;
    private static final int LEAF_STROKE = 0xC0E0E040;
    private static final int OPEN_STROKE = 0xFFFF8C00;
    private static final int OPEN_FILL = 0x70FF8C00;
    private static final int BREAK_STROKE = 0xC0FFC040;
    private static final int BREAK_FILL = 0x30FFC040;
    private static final int RISE_STROKE = 0xFF4090FF;
    private static final int RISE_FILL = 0x604090FF;
    private static final int LAST_STROKE = 0xC0FF8C00;
    private static final int LAST_FILL = 0x30FF8C00;

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
                    render(player);
                }
            }
        });
    }

    /** Flips the view for this player; returns whether it is now on. */
    public static boolean toggle(MinecraftServer server, ServerPlayer player) {
        Set<UUID> watching = WATCHERS.computeIfAbsent(server, s -> new HashSet<>());
        if (watching.remove(player.getUUID())) {
            CellOverlays.clear(player, SOURCE);
            return false;
        }
        watching.add(player.getUUID());
        render(player); // the first frame lands with the reply, not a cadence later
        return true;
    }

    /** One frame: every feller in range that has looked, painted. */
    private static void render(ServerPlayer player) {
        List<CellOverlayPayload.Group> groups = new ArrayList<>();
        List<CellOverlayPayload.Label> labels = new ArrayList<>();
        for (Person person : player.level().getEntitiesOfClass(Person.class,
                player.getBoundingBox().inflate(RANGE), Person::isAlive)) {
            person.brain().executor().currentPrimitive()
                    .filter(FellTree.class::isInstance)
                    .map(FellTree.class::cast)
                    .filter(fell -> fell.approach().isPresent())
                    .ifPresent(fell -> paint(person.getName().getString(), fell, groups, labels));
        }
        if (groups.isEmpty()) {
            CellOverlays.clear(player, SOURCE); // nobody felling should show nothing, not linger
            return;
        }
        CellOverlays.show(player,
                new CellOverlayPayload(SOURCE, TTL_TICKS, groups, List.of(), List.of(), labels));
    }

    private static void paint(String feller, FellTree fell,
                              List<CellOverlayPayload.Group> groups,
                              List<CellOverlayPayload.Label> labels) {
        Approach approach = fell.approach().orElseThrow();
        groups.add(new CellOverlayPayload.Group(WHITE, BASE_WIDTH, BASE_FILL, true,
                cells(approach.base())));
        Pos chosen = fell.chosen().orElse(null);
        for (Approach.Side side : approach.sides()) {
            boolean taken = side.cell().equals(chosen);
            int stroke = colour(side.verdict());
            int fill = (stroke & 0x00FFFFFF) | 0x30000000;
            groups.add(new CellOverlayPayload.Group(stroke, WIDTH, fill, true,
                    List.of(at(side.cell()))));
            if (side.feet() != null && (taken || !side.feet().equals(side.cell()))) {
                groups.add(new CellOverlayPayload.Group(taken ? WHITE : stroke, WIDTH,
                        (stroke & 0x00FFFFFF) | 0x70000000, true, List.of(at(side.feet()))));
            }
            if (!side.leaves().isEmpty()) {
                groups.add(new CellOverlayPayload.Group(LEAF_STROKE, THIN, 0, true,
                        cells(side.leaves())));
            }
            labels.add(new CellOverlayPayload.Label(
                    (taken ? "▶ " : "") + approach.bearing(side.cell()) + " "
                            + side.describe() + " (" + Approach.fmt(side.score()) + ")", stroke,
                    new BlockPos(side.cell().x(), side.cell().y() + 2, side.cell().z())));
        }
        fell.climb().ifPresent(climb -> paint(climb, groups, labels));
        Pos anchor = approach.anchor();
        labels.add(new CellOverlayPayload.Label(feller + ": " + fell.phase(),
                WHITE, new BlockPos(anchor.x(), anchor.y() + 3, anchor.z())));
    }

    private static void paint(Climb climb, List<CellOverlayPayload.Group> groups,
                              List<CellOverlayPayload.Label> labels) {
        if (!climb.stepIn().isEmpty()) {
            groups.add(new CellOverlayPayload.Group(OPEN_STROKE, WIDTH, OPEN_FILL, true,
                    cells(climb.stepIn())));
        }
        groups.add(new CellOverlayPayload.Group(WHITE, WIDTH, BASE_FILL, true,
                List.of(at(climb.stand()))));
        if (!climb.above().isEmpty()) {
            groups.add(new CellOverlayPayload.Group(BREAK_STROKE, THIN, BREAK_FILL, true,
                    cells(climb.above())));
        }
        List<BlockPos> rises = new ArrayList<>();
        for (int y = climb.stand().y(); y < climb.stand().y() + climb.rises(); y++) {
            rises.add(new BlockPos(climb.stand().x(), y, climb.stand().z()));
        }
        if (!rises.isEmpty()) {
            groups.add(new CellOverlayPayload.Group(RISE_STROKE, WIDTH, RISE_FILL, true, rises));
        }
        List<Pos> down = new ArrayList<>(climb.under());
        down.addAll(climb.last());
        if (!down.isEmpty()) {
            groups.add(new CellOverlayPayload.Group(LAST_STROKE, THIN, LAST_FILL, true,
                    cells(down)));
        }
        int top = climb.stand().y();
        for (Pos log : climb.above()) {
            top = Math.max(top, log.y());
        }
        labels.add(new CellOverlayPayload.Label("plan: " + climb.describe(), OPEN_STROKE,
                new BlockPos(climb.stand().x(), top + 2, climb.stand().z())));
    }

    /** Verdict → paint. Green is walkable now, blue and cyan are walkable at another height,
     *  yellow wants clearing, red is refused for shape, grey was never seen. */
    private static int colour(Approach.Verdict verdict) {
        return switch (verdict) {
            case OPEN -> 0xFF40E060;
            case RAISED -> 0xFF4090FF;
            case SUNKEN -> 0xFF40E0E0;
            case LEAVES -> 0xFFE0E040;
            case WATER -> 0xFF2060C0;
            case WOOD -> 0xFFFF3FD4;
            case TOO_HIGH, TOO_DEEP, NO_ROOM -> 0xFFFF4040;
            case UNSEEN -> 0xFFB4B4B4;
        };
    }

    private static BlockPos at(Pos cell) {
        return new BlockPos(cell.x(), cell.y(), cell.z());
    }

    private static List<BlockPos> cells(Iterable<Pos> cells) {
        List<BlockPos> out = new ArrayList<>();
        for (Pos cell : cells) {
            out.add(at(cell));
        }
        return out;
    }
}
