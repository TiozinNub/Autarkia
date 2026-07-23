package dev.luizloyola.autarkia.mod.nav;

import dev.luizloyola.autarkia.compat.nav.WorldSnapshot;
import dev.luizloyola.autarkia.core.nav.AgentProfile;
import dev.luizloyola.autarkia.core.nav.CellType;
import dev.luizloyola.autarkia.core.nav.Path;
import dev.luizloyola.autarkia.core.nav.PathRequest;
import dev.luizloyola.autarkia.core.nav.Pathfinder;
import dev.luizloyola.autarkia.mod.AutarkiaMod;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import org.jspecify.annotations.Nullable;

/**
 * Turns "this Person wants to reach that block" into a {@link Path}. Everything that reads the live
 * world (snapshot capture, goal grounding) happens on the server thread inside
 * {@link #request}/{@link #computeNow}; the A* only ever sees the immutable {@link WorldSnapshot},
 * so it can run anywhere.
 *
 * <p>{@link #request} searches on the shared executor and returns a future the {@link Navigator}
 * polls from its own tick, so a path is only ever <em>applied</em> on the main thread.
 * {@link #computeNow} runs the identical pipeline synchronously — the debugging escape hatch.
 */
public final class PathfinderService {
    private PathfinderService() {}

    /** How far past the start∪goal box the snapshot extends, so detours have room to route. */
    private static final int HORIZONTAL_MARGIN = 16;
    private static final int UP_MARGIN = 6;
    private static final int DOWN_MARGIN = 10;
    /**
     * Snapshot half-extent cap: a goal further than this gets a snapshot clamped around the start
     * and a partial path toward it (the navigator re-paths from the partial end, so long trips
     * happen leg by leg instead of baking enormous boxes).
     */
    private static final int MAX_REACH = 96;
    /** How far below a clicked goal to look for actual ground (clicks land on faces, not floors). */
    private static final int GOAL_DROP_SCAN = 12;

    private static final int WORKER_THREADS = 2;

    private static @Nullable ExecutorService executor;

    /**
     * Same-tick snapshot reuse: bodies dispatched in one tick share one capture instead of N of
     * the same terrain. Older than the current tick is stale by definition — conservative
     * freshness, per the design doc's open question.
     */
    private static final Map<ServerLevel, CachedSnapshot> snapshots = new HashMap<>();

    private record CachedSnapshot(WorldSnapshot snapshot, long gameTime) {}

    /** Call once from mod init: ties the worker pool and snapshot cache to the server lifecycle. */
    public static void init() {
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (executor != null) {
                executor.shutdownNow(); // in-flight searches read only private snapshots; safe to kill
                executor = null;
            }
            snapshots.clear();
        });
    }

    /**
     * The result plus the snapshot it searched. The navigator holds that snapshot for the life
     * of the path: the follower reads it for edge awareness (slow-down near deep drops),
     * guaranteed consistent with what the path was planned against.
     */
    public record Dispatched(CompletableFuture<Path> result, WorldSnapshot snapshot) {}

    /**
     * Asynchronous pathfinding: snapshot on this (the server) thread, search on a worker. The
     * future completes on the worker — consume it by polling from a tick, never with a callback
     * that touches the world.
     */
    public static Dispatched request(ServerLevel level, BlockPos start, BlockPos goal) {
        WorldSnapshot snapshot = sharedSnapshot(level, start, goal);
        PathRequest pathRequest = buildRequest(snapshot, start, goal);
        CompletableFuture<Path> result = CompletableFuture.supplyAsync(() -> {
            Path path = Pathfinder.find(snapshot, pathRequest);
            // Dev-phase trace; doubles as proof the search left the server thread (log prefix).
            AutarkiaMod.LOGGER.info("path {} -> {}: {} waypoints{}",
                    start.toShortString(), goal.toShortString(),
                    path.waypoints().size(), path.reachedGoal() ? "" : " (partial)");
            return path;
        }, executor());
        return new Dispatched(result, snapshot);
    }

    /** The same pipeline as {@link #request}, entirely on the calling (server) thread. */
    public static Dispatched computeNow(ServerLevel level, BlockPos start, BlockPos goal) {
        WorldSnapshot snapshot = sharedSnapshot(level, start, goal);
        Path path = Pathfinder.find(snapshot, buildRequest(snapshot, start, goal));
        return new Dispatched(CompletableFuture.completedFuture(path), snapshot);
    }

    private static PathRequest buildRequest(WorldSnapshot snapshot, BlockPos start, BlockPos goal) {
        BlockPos grounded = groundGoal(snapshot, goal, AgentProfile.PERSON.canSwim());
        return PathRequest.of(start.getX(), start.getY(), start.getZ(),
                grounded.getX(), grounded.getY(), grounded.getZ(), AgentProfile.PERSON);
    }

    private static WorldSnapshot sharedSnapshot(ServerLevel level, BlockPos start, BlockPos goal) {
        int gx = Mth.clamp(goal.getX(), start.getX() - MAX_REACH, start.getX() + MAX_REACH);
        int gy = goal.getY();
        int gz = Mth.clamp(goal.getZ(), start.getZ() - MAX_REACH, start.getZ() + MAX_REACH);
        BlockPos min = new BlockPos(
                Math.min(start.getX(), gx) - HORIZONTAL_MARGIN,
                Math.min(start.getY(), gy) - DOWN_MARGIN,
                Math.min(start.getZ(), gz) - HORIZONTAL_MARGIN);
        BlockPos max = new BlockPos(
                Math.max(start.getX(), gx) + HORIZONTAL_MARGIN,
                Math.max(start.getY(), gy) + UP_MARGIN,
                Math.max(start.getZ(), gz) + HORIZONTAL_MARGIN);

        CachedSnapshot cached = snapshots.get(level);
        if (cached != null && cached.gameTime() == level.getGameTime() && cached.snapshot().covers(min, max)) {
            return cached.snapshot();
        }
        WorldSnapshot fresh = WorldSnapshot.capture(level, min, max);
        snapshots.put(level, new CachedSnapshot(fresh, level.getGameTime()));
        return fresh;
    }

    /**
     * Clicks and commands rarely name a standable cell (a block face, a spot mid-air): walks the
     * goal down to the first cell with ground under it and room to stand. Finding none, the goal
     * stands and the search yields its best partial toward it.
     *
     * <p>For a swimmer a goal over open water settles at the <em>surface</em> (first water cell
     * with air above), not the lakebed: surface crossing cannot reach the bed.
     */
    private static BlockPos groundGoal(WorldSnapshot snapshot, BlockPos goal, boolean canSwim) {
        int x = goal.getX();
        int z = goal.getZ();
        for (int y = goal.getY(); y > goal.getY() - GOAL_DROP_SCAN; y--) {
            if (canSwim && snapshot.cell(x, y, z) == CellType.WATER
                    && snapshot.cell(x, y + 1, z) == CellType.PASSABLE) {
                return new BlockPos(x, y, z); // the water surface — a swimmer floats here
            }
            CellType below = snapshot.cell(x, y - 1, z);
            if (below == CellType.GROUND
                    && snapshot.cell(x, y, z) == CellType.PASSABLE
                    && snapshot.cell(x, y + 1, z) == CellType.PASSABLE) {
                return new BlockPos(x, y, z);
            }
            if (below == CellType.OBSTACLE || below == CellType.DANGER) {
                break; // solid-but-unstandable or harmful floor: nothing walkable further down
            }
        }
        return goal;
    }

    private static ExecutorService executor() {
        if (executor == null) {
            ThreadFactory factory = new ThreadFactory() {
                private final AtomicInteger id = new AtomicInteger();

                @Override
                public Thread newThread(Runnable task) {
                    Thread thread = new Thread(task, "autarkia-pathfinder-" + this.id.getAndIncrement());
                    thread.setDaemon(true);
                    return thread;
                }
            };
            executor = Executors.newFixedThreadPool(WORKER_THREADS, factory);
        }
        return executor;
    }
}
