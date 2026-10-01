package dev.luizloyola.autarkia.core.direction;

import dev.luizloyola.anima.core.brain.knowledge.Region;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.core.territory.ChunkKey;
import dev.luizloyola.autarkia.core.board.ClearPlants;
import dev.luizloyola.autarkia.core.board.FellTrees;
import dev.luizloyola.autarkia.core.board.PartyProject;
import dev.luizloyola.autarkia.core.board.Project;
import dev.luizloyola.autarkia.core.tree.TreeFelling;
import java.util.Optional;

/**
 * {@code area}: every chunk of HOME's area has been cleared, each once
 * (docs/superpowers/specs/2026-10-01-home-area-design.md, decision 4): its trees felled, then its
 * plants pulled up.
 *
 * <p>What the party knows of a chunk is its own work's ledger, and nothing else — nobody shares the
 * trees they walked past. So a chunk is felled once a felling of it has finished, and a chunk the
 * area grows into is one nobody has touched. One job is out at a time, on the unfinished chunk
 * nearest the party's stores: a felling's slicing and ledger are a single region's.
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
        return home.get().uncleared(party.area()).isEmpty()
                ? Status.of(Status.Reading.MET, "autarkia.direction.area.cleared")
                : Status.of(Status.Reading.UNMET, "autarkia.direction.area.uncleared");
    }

    @Override
    public boolean isWork(Project project, Direction direction, PartyView party) {
        Optional<Region> bounds = boundsOf(project);
        if (bounds.isEmpty() || party.home().isEmpty()) {
            return false;
        }
        return Home.chunkOf(bounds.get()).filter(party.area()::contains).isPresent();
    }

    @Override
    public PartyProject post(Direction direction, PartyView party, double priority) {
        Home home = party.home().orElseThrow();
        // The base stands before this line posts, so there is a store and with it a spot.
        dev.luizloyola.anima.core.brain.sense.Pos spot = party.spot().orElseThrow();
        ChunkKey next = home.uncleared(party.area(), spot).get(0);
        Region bounds = Home.region(next, spot.y());
        return home.felled().contains(next)
                ? new ClearPlants(bounds, priority)
                : new FellTrees(TreeFelling.INSTANCE, bounds, priority, true);
    }

    @Override
    public void finished(Project project, Direction direction, PartyProgress progress) {
        Home home = progress.home();
        if (home == null) {
            return;
        }
        if (project instanceof FellTrees felling) {
            Home.chunkOf(felling.bounds()).ifPresent(chunk -> progress.home(home.withFelled(chunk)));
        } else if (project instanceof ClearPlants plants) {
            Home.chunkOf(plants.bounds()).ifPresent(chunk -> progress.home(home.withCleared(chunk)));
        }
    }

    private static Optional<Region> boundsOf(Project project) {
        if (project instanceof FellTrees felling) {
            return Optional.of(felling.bounds());
        }
        if (project instanceof ClearPlants plants) {
            return Optional.of(plants.bounds());
        }
        return Optional.empty();
    }
}
