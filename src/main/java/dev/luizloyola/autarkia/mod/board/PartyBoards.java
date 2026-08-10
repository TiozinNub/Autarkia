package dev.luizloyola.autarkia.mod.board;

import dev.luizloyola.anima.core.social.PartyId;
import dev.luizloyola.autarkia.core.board.PartyBoard;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;

/**
 * The {@code mod} home of the party boards: one {@link PartyBoard} per {@link PartyId} per running
 * server, ticked here rather than by anybody's entity — the {@code Claims} / {@code Journals}
 * shape. Ticked from a member it would think once per member, stop thinking when the last member
 * unloaded, and have to decide whose eyes it thought with.
 *
 * <p><b>Staggered</b> by board id, so a settlement's worth of boards spread across the interval
 * instead of spiking on one tick.
 *
 * <p>Persisted ({@link PartyBoardData}): the store does not mirror the boards, it asks them what
 * they hold as vanilla serializes; this host only says whether anything is worth asking about.
 */
public final class PartyBoards {
    private PartyBoards() {}

    /** Ticks between one board's beats. Slow on purpose: a project's cadence, not a body's. */
    public static final int TICK_INTERVAL = 40;

    /** One registry per live server; dropped on stop. Server-thread only, like the journals. */
    private static final Map<MinecraftServer, Map<PartyId, PartyBoard>> BY_SERVER = new HashMap<>();

    /** Call once from mod init: ties the per-server registries and their cadence to the lifecycle. */
    public static void init() {
        // STARTED, not STARTING: the levels have to exist before the store can be read, and the
        // boards must be standing before the first tick could offer anybody an errand.
        ServerLifecycleEvents.SERVER_STARTED.register(PartyBoards::load);
        // STOPPED, not STOPPING. Vanilla saves its level data AFTER the stopping event and this
        // store's codec asks the live boards what they hold as it serializes, so dropping the
        // registry on STOPPING writes an empty list over a settlement's work. Caught live
        // 2026-08-10: the file was there, at the right version, declaring and holding zero rows,
        // so the boot guard passed it as healthy.
        ServerLifecycleEvents.SERVER_STOPPED.register(BY_SERVER::remove);
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            Map<PartyId, PartyBoard> boards = BY_SERVER.get(server);
            if (boards == null || boards.isEmpty()) {
                return;
            }
            long now = server.overworld().getGameTime();
            boolean anythingPosted = false;
            // Copied because a board's tick may close projects, and later may post or disband —
            // none of which should be a concurrent modification of the map being walked.
            for (PartyBoard board : new ArrayList<>(boards.values())) {
                if (dueThisTick(board.party(), now)) {
                    board.tick(now);
                }
                anythingPosted |= !board.isEmpty();
            }
            if (anythingPosted) {
                touch(server);
            }
        });
    }

    /**
     * Rebuilds every saved board, then tells the store to answer for the live ones from now on.
     *
     * <p>Before anything ticks, so a member who logs in on the first tick finds the errand they
     * were walking to still theirs, not back on offer.
     */
    private static void load(MinecraftServer server) {
        PartyBoardData store = PartyBoardData.get(server);
        long now = server.overworld().getGameTime();
        for (PartyId party : store.parties()) {
            PartyBoardData.refuseUnknown(of(server, party).restore(store.take(party), now), party);
        }
        store.attach(server);
    }

    /**
     * Marks the store dirty, so the next save asks the boards what they hold.
     *
     * <p>Called from the tick above while anything is posted, rather than from each of the many
     * places a board changes: missing one of those loses a settlement's work silently, while a
     * flag set on a slow beat while a board is non-empty cannot miss any. Costs one file write per
     * autosave, only while a party has work posted.
     */
    public static void touch(MinecraftServer server) {
        PartyBoardData.get(server).setDirty();
    }

    /** This party's board on this server, created empty on first ask. */
    public static PartyBoard of(MinecraftServer server, PartyId party) {
        return BY_SERVER.computeIfAbsent(server, s -> new HashMap<>())
                .computeIfAbsent(party, PartyBoard::new);
    }

    /** Every board this server currently holds — the operator readout's source. */
    public static List<PartyBoard> all(MinecraftServer server) {
        Map<PartyId, PartyBoard> boards = BY_SERVER.get(server);
        return boards == null ? List.of() : List.copyOf(boards.values());
    }

    /** Whether this party's staggered beat falls on this tick. */
    private static boolean dueThisTick(PartyId party, long now) {
        int offset = Math.floorMod(party.value().hashCode(), TICK_INTERVAL);
        return Math.floorMod(now, TICK_INTERVAL) == offset;
    }
}
