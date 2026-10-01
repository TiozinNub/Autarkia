package dev.luizloyola.autarkia.mod.board;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.craft.Furnace;
import dev.luizloyola.anima.core.log.Category;
import dev.luizloyola.anima.core.log.JournalService;
import dev.luizloyola.anima.mod.log.Journals;
import dev.luizloyola.anima.mod.social.PartyData;
import dev.luizloyola.anima.core.social.PartyId;
import dev.luizloyola.anima.core.social.PlaceRow;
import dev.luizloyola.anima.core.social.Process;
import dev.luizloyola.anima.mod.social.PlacesData;
import dev.luizloyola.autarkia.core.board.PartyBoard;
import dev.luizloyola.autarkia.core.board.Project;
import dev.luizloyola.autarkia.core.board.Stock;
import dev.luizloyola.autarkia.core.board.Tend;
import dev.luizloyola.autarkia.core.direction.Home;
import dev.luizloyola.autarkia.core.direction.PartyProgress;
import dev.luizloyola.autarkia.mod.direction.DirectionsData;
import java.util.HashMap;
import java.util.Map;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;

/**
 * Posts a {@link Tend} on a party's board when a process at one of its furnaces falls due — the
 * board half of decision 21. One per place at a time. A follow-up that left the process due (a
 * furnace with nothing to burn and no fuel to be had) is not posted again for {@link #REPOST}.
 */
public final class Tending {

    private static final int INTERVAL = 40;
    static final long REPOST = 600;

    /** When each place's last follow-up closed with the process still due. Transient. */
    private static final Map<PlaceRow, Long> RESTED = new HashMap<>();

    private Tending() {
    }

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            long now = server.overworld().getGameTime();
            if (now % INTERVAL == 0) {
                watch(server, now);
            }
        });
        PartyBoards.onClosed((server, party, finished) -> {
            long now = server.overworld().getGameTime();
            for (Project project : finished) {
                if (project instanceof Tend tend) {
                    PlacesData.get(server).places().processes().forEach((row, process) -> {
                        if (row.at().equals(tend.at()) && process.due(now)) {
                            RESTED.put(row, now);
                        }
                    });
                }
            }
        });
    }

    private static void watch(MinecraftServer server, long now) {
        Map<PlaceRow, Process> running = PlacesData.get(server).places().processes();
        RESTED.keySet().retainAll(running.keySet());
        running.forEach((row, process) -> {
            PartyId party = row.party();
            if (party == null || row.kind() != Furnace.POI || !process.due(now)) {
                return;
            }
            Long rested = RESTED.get(row);
            if (rested != null && now - rested < REPOST) {
                return;
            }
            PartyBoard board = PartyBoards.of(server, party);
            for (Project posted : board.projects()) {
                if (posted instanceof Tend tend && !tend.finished() && tend.at().equals(row.at())) {
                    return;
                }
            }
            Home home = DirectionsData.get(server).find(party).map(PartyProgress::home).orElse(null);
            Tend tend = new Tend(row.at(), process.output(), Stock.PLANKS, home == null ? null : home.yard(),
                    process.starter(), process.dueAt());
            int handle = board.post(tend);
            JournalService journal = Journals.of(server);
            for (AgentId member : PartyData.get(server).members(party)) {
                journal.record(member, Category.PROJECT, "tending", "posted #" + handle + " " + tend.describe());
            }
            PartyBoards.touch(server);
        });
    }
}
