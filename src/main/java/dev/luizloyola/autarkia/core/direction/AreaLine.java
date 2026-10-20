package dev.luizloyola.autarkia.core.direction;

import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.core.territory.ChunkKey;
import dev.luizloyola.autarkia.core.board.FellTrees;
import dev.luizloyola.autarkia.core.board.PartyProject;
import dev.luizloyola.autarkia.core.board.Project;
import dev.luizloyola.autarkia.core.tree.TreeFelling;
import java.util.Optional;

/**
 * {@code area}: the trees on every chunk of HOME's area have been felled, each chunk once
 * (docs/superpowers/specs/2026-10-01-home-area-design.md, decision 4). Plants are not this line's:
 * a building fells and pulls up those round its own pad (Luiz, 2026-10-20).
 *
 * <p>What the party knows of a chunk is its own work's ledger, and nothing else — nobody shares the
 * trees they walked past. So a chunk is felled once a felling of it has finished, and a chunk the
 * area grows into is one nobody has touched. One job is out at a time, on the unfelled chunk nearest
 * the party's stores: a felling's slicing and ledger are a single region's.
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
        ChunkKey next = home.unfelled(party.area(), spot).get(0);
        return new FellTrees(TreeFelling.INSTANCE, Home.region(next, spot.y()), priority, true, party.members());
    }

    @Override
    public void finished(Project project, Direction direction, PartyProgress progress) {
        Home home = progress.home();
        if (home != null && project instanceof FellTrees felling) {
            Home.chunkOf(felling.bounds()).ifPresent(chunk -> progress.home(home.withFelled(chunk)));
        }
    }
}
