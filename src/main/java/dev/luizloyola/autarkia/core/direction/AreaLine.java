package dev.luizloyola.autarkia.core.direction;

import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.core.territory.ChunkKey;
import dev.luizloyola.autarkia.core.board.FellTrees;
import dev.luizloyola.autarkia.core.board.PartyProject;
import dev.luizloyola.autarkia.core.board.Project;
import dev.luizloyola.autarkia.core.builder.Structure;
import dev.luizloyola.autarkia.core.tree.TreeFelling;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * {@code area}: the trees on every chunk of HOME's area have been felled, each chunk once
 * (docs/superpowers/specs/2026-10-01-home-area-design.md, decision 4). Plants are not this line's:
 * a building pulls up those round its own pad (Luiz, 2026-10-20).
 *
 * <p>What the party knows of a chunk is its own work's ledger, and nothing else — nobody shares the
 * trees they walked past. So a chunk is felled once a felling of it has finished, and a chunk the
 * area grows into is one nobody has touched. One felling is out at a time, a felling's slicing and
 * ledger being a single region's: on the ground of a building waiting for it first, since the house
 * waits on nothing else, then on the unfelled chunk nearest the party's stores.
 */
public final class AreaLine implements DirectionLine {

    public static final AreaLine INSTANCE = new AreaLine();

    private AreaLine() {
    }

    @Override
    public String id() {
        return "area";
    }

    @Override
    public Optional<ItemSpec> seeks() {
        return Optional.empty();
    }

    @Override
    public Status judge(Direction direction, PartyView party) {
        Optional<Home> home = party.home();
        if (home.isEmpty()) {
            return Status.NO_HOME;
        }
        if (!party.baseReady()) {
            return Status.NO_BASE;
        }
        return home.get().unfelled(party.area()).isEmpty()
                ? Status.of(Status.Reading.MET, "autarkia.direction.area.felled")
                : Status.of(Status.Reading.UNMET, "autarkia.direction.area.unfelled");
    }

    @Override
    public boolean isWork(Project project, Direction direction, PartyView party) {
        if (!(project instanceof FellTrees felling) || party.home().isEmpty()) {
            return false;
        }
        return Home.chunkOf(felling.bounds()).filter(party.area()::contains).isPresent();
    }

    @Override
    public PartyProject post(Direction direction, PartyView party, double priority) {
        Home home = party.home().orElseThrow();
        // The base stands before this line posts, so there is a store and with it a spot.
        Pos spot = party.spot().orElseThrow();
        Set<ChunkKey> waited = waitedOn(party);
        ChunkKey next = home.unfelled(party.area(), spot, waited).get(0);
        // A building's ground is the house's critical path: it bids no less than the house does.
        double bid = waited.contains(next) ? Math.max(priority, Direction.TOWARD_CORE) : priority;
        return new FellTrees(TreeFelling.INSTANCE, Home.region(next, spot.y()), bid, true, party.members());
    }

    /** The ground chunks of the party's buildings still waiting for their trees to be felled. */
    private static Set<ChunkKey> waitedOn(PartyView party) {
        Set<ChunkKey> chunks = new TreeSet<>();
        for (Structure structure : party.structures()) {
            if (structure.phase() == Structure.Phase.SITED) {
                chunks.addAll(structure.groundChunks());
            }
        }
        return chunks;
    }

    @Override
    public void finished(Project project, Direction direction, PartyProgress progress) {
        Home home = progress.home();
        if (home != null && project instanceof FellTrees felling) {
            Home.chunkOf(felling.bounds()).ifPresent(chunk -> progress.home(home.withFelled(chunk)));
        }
    }
}
