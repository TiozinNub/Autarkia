package dev.luizloyola.autarkia.core.direction;

import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.core.territory.ChunkKey;
import dev.luizloyola.autarkia.core.board.FellTrees;
import dev.luizloyola.autarkia.core.board.PartyProject;
import dev.luizloyola.autarkia.core.board.Project;
import dev.luizloyola.autarkia.core.tree.TreeFelling;
import java.util.List;
import java.util.Optional;

/**
 * {@code area}: every chunk of HOME's area has been cleared, each once
 * (docs/superpowers/specs/2026-10-01-home-area-design.md, decision 4).
 *
 * <p>What the party knows of a chunk is its own clearing's ledger, and nothing else — nobody shares
 * the trees they walked past. So a chunk is cleared once a clearing of it has finished, and a chunk
 * the area grows into is one nobody has cleared. One chunk is out at a time, the nearest the yard
 * first: a clearing's slicing and ledger are a single region's.
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
        if (!(project instanceof FellTrees clearing) || party.home().isEmpty()) {
            return false;
        }
        Home home = party.home().get();
        return home.chunkOf(clearing.bounds()).filter(party.area()::contains).isPresent();
    }

    @Override
    public PartyProject post(Direction direction, PartyView party, double priority) {
        Home home = party.home().orElseThrow();
        List<ChunkKey> left = home.uncleared(party.area());
        return new FellTrees(TreeFelling.INSTANCE, home.region(left.get(0)), priority, home.yard());
    }

    @Override
    public void finished(Project project, Direction direction, PartyProgress progress) {
        Home home = progress.home();
        if (home != null && project instanceof FellTrees clearing) {
            home.chunkOf(clearing.bounds()).ifPresent(chunk -> progress.home(home.withCleared(chunk)));
        }
    }
}
