package dev.luizloyola.autarkia.core.direction;

import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.autarkia.core.board.PartyProject;
import dev.luizloyola.autarkia.core.board.Project;
import dev.luizloyola.autarkia.core.board.SetUp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@code base}: HOME has a workbench and a chest the party claims — what a settler sets up before
 * anything else, and what every line that hauls to HOME waits for.
 *
 * <p>Judged from the party's claimed places, which a Direction may read. Whatever is missing is
 * posted as one {@link SetUp}, workbench first, so the chest is crafted at HOME's own table rather
 * than at one dropped wherever its maker happened to stand.
 */
public final class BaseLine implements DirectionLine {

    public static final BaseLine INSTANCE = new BaseLine();

    /** What a base is, in the order it goes down. */
    public static final List<SetUp.Station> STATIONS = List.of(SetUp.WORKBENCH, SetUp.STORE);

    private BaseLine() {
    }

    @Override
    public String id() {
        return "base";
    }

    @Override
    public Optional<ItemSpec> seeks() {
        return Optional.empty();
    }

    @Override
    public Status judge(Direction direction, PartyView party) {
        // A HOME whose area could not be had has nowhere for the base to go.
        if (party.home().isEmpty() || party.area().isEmpty()) {
            return Status.NO_HOME;
        }
        List<SetUp.Station> missing = missing(party);
        if (!missing.isEmpty() && party.building()) {
            // The builder spec's *The base moves*: gone for the length of the build, which replaces it.
            return Status.of(Status.Reading.WAITING, "autarkia.direction.base.building");
        }
        return missing.isEmpty()
                ? Status.of(Status.Reading.MET, "autarkia.direction.base.ready")
                : Status.of(Status.Reading.UNMET, "autarkia.direction.base.missing",
                        STATIONS.size() - missing.size(), STATIONS.size());
    }

    /**
     * A set-up in HOME's area that would put down something the base lacks. Not every set-up there:
     * once the base stands, the storage line's extra chest is not the base's work, and claiming it
     * would have the met base withdraw it every beat while storage posted it again.
     */
    @Override
    public boolean isWork(Project project, Direction direction, PartyView party) {
        if (!(project instanceof SetUp setUp) || party.home().isEmpty() || !inArea(party, setUp.near())) {
            return false;
        }
        List<SetUp.Station> missing = missing(party);
        return setUp.remaining().stream().anyMatch(missing::contains);
    }

    @Override
    public PartyProject post(Direction direction, PartyView party, double priority) {
        return new SetUp(missing(party), party.spot().orElseThrow(), Math.max(priority, Direction.BUILDING));
    }

    /** Whether a set-up's spot is in the party's area: how a line knows the set-up is HOME's. */
    static boolean inArea(PartyView party, dev.luizloyola.anima.core.brain.sense.Pos at) {
        return party.area().contains(dev.luizloyola.anima.core.territory.ChunkKey.at(
                dev.luizloyola.anima.core.territory.ChunkKey.OVERWORLD, at.x(), at.z()));
    }

    private static List<SetUp.Station> missing(PartyView party) {
        List<SetUp.Station> missing = new ArrayList<>();
        for (SetUp.Station station : STATIONS) {
            if (!party.hasAtHome(station.kind())) {
                missing.add(station);
            }
        }
        return missing;
    }
}
