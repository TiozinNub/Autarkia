package dev.luizloyola.autarkia.core.direction;

import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.autarkia.core.board.PartyProject;
import dev.luizloyola.autarkia.core.board.Project;
import dev.luizloyola.autarkia.core.board.SetUp;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * {@code storage}: HOME's stores have at least {@code count} empty slots between them — "I need
 * more room at my base", asked of the base rather than of whatever happens to be hauling to it.
 *
 * <p>Posts one more chest at a time. A Direction never posts the same work twice, so a party short
 * of room gets one chest, not one per member who noticed.
 */
public final class StorageLine implements DirectionLine {

    public static final StorageLine INSTANCE = new StorageLine();

    private StorageLine() {
    }

    @Override
    public String id() {
        return "storage";
    }

    @Override
    public Optional<ItemSpec> seeks() {
        return Optional.empty();
    }

    @Override
    public Optional<String> check(Direction direction) {
        return direction.count() < 1 ? Optional.of("needs a count of at least 1") : Optional.empty();
    }

    @Override
    public Status judge(Direction direction, PartyView party) {
        if (party.home().isEmpty()) {
            return Status.NO_HOME;
        }
        if (!party.baseReady()) {
            return Status.NO_BASE;
        }
        OptionalInt free = party.freeSlotsAtHome();
        if (free.isEmpty()) {
            return Status.UNREAD;
        }
        return Status.of(free.getAsInt() >= direction.count() ? Status.Reading.MET : Status.Reading.UNMET,
                "autarkia.direction.storage.free", free.getAsInt(), direction.count());
    }

    @Override
    public boolean isWork(Project project, Direction direction, PartyView party) {
        return project instanceof SetUp setUp && setUp.remaining().contains(SetUp.STORE)
                && party.home().map(home -> home.yard().equals(setUp.near())).orElse(false);
    }

    @Override
    public PartyProject post(Direction direction, PartyView party, double priority) {
        return new SetUp(List.of(SetUp.STORE), party.home().orElseThrow().yard(), priority);
    }
}
