package dev.luizloyola.autarkia.core.direction;

import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.autarkia.core.board.ClearArea;
import dev.luizloyola.autarkia.core.board.PartyProject;
import dev.luizloyola.autarkia.core.board.Project;
import dev.luizloyola.autarkia.core.tree.TreeClearing;
import java.util.Optional;

/**
 * {@code area}: the plot holds no tree the party knows of.
 *
 * <p>What the party knows of the plot is its own clearing's ledger, and nothing else — nobody shares
 * the trees they walked past. So the condition holds once a clearing of this plot has finished,
 * and a new HOME is a plot nobody has cleared.
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
        return home.get().cleared()
                ? Status.of(Status.Reading.MET, "autarkia.direction.area.cleared")
                : Status.of(Status.Reading.UNMET, "autarkia.direction.area.uncleared");
    }

    @Override
    public boolean isWork(Project project, Direction direction, PartyView party) {
        return project instanceof ClearArea clearing
                && party.home().map(home -> home.plot().equals(clearing.bounds())).orElse(false);
    }

    @Override
    public PartyProject post(Direction direction, PartyView party, double priority) {
        Home home = party.home().orElseThrow();
        return new ClearArea(TreeClearing.INSTANCE, home.plot(), priority, home.yard());
    }

    @Override
    public void finished(Project project, Direction direction, PartyProgress progress) {
        Home home = progress.home();
        if (home != null && project instanceof ClearArea clearing
                && home.plot().equals(clearing.bounds())) {
            progress.home(home.withCleared(true));
        }
    }
}
