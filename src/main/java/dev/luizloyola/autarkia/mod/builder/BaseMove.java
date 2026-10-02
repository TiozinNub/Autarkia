package dev.luizloyola.autarkia.mod.builder;

import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.social.PartyId;
import dev.luizloyola.anima.core.social.PlaceRow;
import dev.luizloyola.anima.core.social.Places;
import dev.luizloyola.anima.mod.social.PlacesData;
import dev.luizloyola.autarkia.core.board.Deconstruct;
import dev.luizloyola.autarkia.core.board.PartyBoard;
import dev.luizloyola.autarkia.core.board.Project;
import dev.luizloyola.autarkia.core.board.SetUp;
import dev.luizloyola.autarkia.core.bp.Footprint;
import dev.luizloyola.autarkia.core.builder.Structure;
import dev.luizloyola.autarkia.mod.board.PartyBoards;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.MinecraftServer;
import org.jspecify.annotations.Nullable;

/**
 * A site over the party's base (decision 10): the base is taken off it before the build, as the
 * builder spec's *The base moves* has it. The stations on the pad are released and taken down by a
 * {@link Deconstruct}, a chest's goods carried to the party's stores — HOME's spot keeps off a site
 * being built, so a store missing is made beside it, and the base line sets up what else went. The
 * house replaces them when it is moved into.
 */
final class BaseMove {

    private static final List<SetUp.Station> STATIONS = List.of(SetUp.WORKBENCH, SetUp.STORE, SetUp.FURNACE);

    /** Ahead of the build it clears the way for. */
    private static final double PRIORITY = 0.55;

    private BaseMove() {
    }

    /**
     * Station set-ups on a site while its building is levelled or going up: withdrawn, or one would
     * put a station down there and the build break it. A line waiting on the base keeps what it
     * posted, so they are taken off here. One elsewhere stays: the house's torches wait on a furnace.
     */
    static void holdSetUps(MinecraftServer server, PartyId party, List<Structure> structures) {
        PartyBoard board = PartyBoards.of(server, party);
        boolean changed = false;
        for (Project project : List.copyOf(board.projects())) {
            if (project instanceof SetUp setUp && !setUp.finished() && SetUp.onASite(structures, setUp.near())) {
                java.util.OptionalInt handle = board.handleOf(setUp);
                if (handle.isPresent()) {
                    board.cancel(handle.getAsInt());
                    changed = true;
                }
            }
        }
        if (changed) {
            PartyBoards.touch(server);
        }
    }

    /**
     * Whether the base still stands in the way of this building: null when the pad is free and the
     * build may go up, else what it waits for.
     */
    static @Nullable String inTheWay(MinecraftServer server, PartyId party, Structure structure) {
        Footprint pad = structure.pad();
        PartyBoard board = PartyBoards.of(server, party);
        for (Project project : board.projects()) {
            if (project instanceof Deconstruct deconstruct && !deconstruct.finished()
                    && deconstruct.targets().stream().anyMatch(t -> inside(pad, t.at()))) {
                return "moving the base off the site";
            }
        }
        Places places = PlacesData.get(server).places();
        List<Deconstruct.Target> targets = new ArrayList<>();
        for (PlaceRow row : List.copyOf(places.rows())) {
            if (!party.equals(row.party()) || !inside(pad, row.at())) {
                continue;
            }
            for (SetUp.Station station : STATIONS) {
                if (station.kind().equals(row.kind())) {
                    // Released first: the emptying must not put the goods back where they were.
                    places.drop(row.kind(), row.at());
                    targets.add(new Deconstruct.Target(row.at(), station.itemId(), station == SetUp.STORE));
                }
            }
        }
        if (targets.isEmpty()) {
            return null;
        }
        board.post(new Deconstruct(targets, "making room for " + structure.blueprint(), PRIORITY));
        PartyBoards.touch(server);
        return "moving the base off the site";
    }

    private static boolean inside(Footprint f, Pos at) {
        return at.x() >= f.minX() && at.x() <= f.maxX() && at.z() >= f.minZ() && at.z() <= f.maxZ();
    }
}
