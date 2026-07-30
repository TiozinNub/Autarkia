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
 * shape.
 *
 * <p>A board ticked from a member would think once per member, stop thinking when the last member
 * unloaded, and have to decide whose eyes it thought with. Ticked from here it thinks once, on its
 * own cadence, with no eyes: state that belongs to a group, not to a body.
 *
 * <p>Staggered — each board's beat is offset by its own id, so a settlement's worth of boards do
 * not all think on the same tick.
 *
 * <p>Transient in ladder step 2: nothing posts to a party board yet, so nothing is lost across a
 * restart. The persisted store (Autarkia SavedData, {@code PartyId}-keyed) lands with the first
 * project that can be posted there.
 */
public final class PartyBoards {
    private PartyBoards() {}

    /** Ticks between one board's beats. Slow on purpose: a project's cadence, not a body's. */
    public static final int TICK_INTERVAL = 40;

    /** One registry per live server; dropped on stop. Server-thread only, like the journals. */
    private static final Map<MinecraftServer, Map<PartyId, PartyBoard>> BY_SERVER = new HashMap<>();

    /** Call once from mod init: ties the per-server registries and their cadence to the lifecycle. */
    public static void init() {
        ServerLifecycleEvents.SERVER_STOPPING.register(BY_SERVER::remove);
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            Map<PartyId, PartyBoard> boards = BY_SERVER.get(server);
            if (boards == null || boards.isEmpty()) {
                return;
            }
            long now = server.overworld().getGameTime();
            // Copied because a board's tick may close projects, and later may post or disband —
            // none of which should be a concurrent modification of the map being walked.
            for (PartyBoard board : new ArrayList<>(boards.values())) {
                if (dueThisTick(board.party(), now)) {
                    board.tick(now);
                }
            }
        });
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
