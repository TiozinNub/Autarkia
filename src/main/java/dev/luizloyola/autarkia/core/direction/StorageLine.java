package dev.luizloyola.autarkia.core.direction;

import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.autarkia.core.board.GrowBuilding;
import dev.luizloyola.autarkia.core.board.PartyProject;
import dev.luizloyola.autarkia.core.board.Project;
import dev.luizloyola.autarkia.core.board.SetUp;
import dev.luizloyola.autarkia.core.builder.Growth;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * {@code storage}: HOME's stores have at least {@code count} empty slots per member between them —
 * "I need more room at my base", asked of the base rather than of whatever happens to be hauling to
 * it.
 *
 * <p><b>Per member</b> (2026-10-02): every member hauls home on their own, so a party-wide number
 * that was a trip's room for three was less than one member's load for sixteen, and the house's
 * one chest filled under a forest's leaf litter with every hauler finding it full.
 *
 * <p>Posts one more chest at a time. A Direction never posts the same work twice, so a party short
 * of room gets one chest, not one per member who noticed.
 *
 * <p><b>A house grows first</b> (2026-10-02): while a building of the party's can grow into more
 * chests — the basic house's {@code base=lv2} — the room is asked of it, and a chest is set up loose
 * only once none can. Set up beside the house's chests, they had filled its floor.
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
        int wanted = direction.count() * party.members();
        if (free.getAsInt() < wanted && party.growing()) {
            return Status.of(Status.Reading.WAITING, "autarkia.direction.storage.growing", free.getAsInt(), wanted);
        }
        return Status.of(free.getAsInt() >= wanted ? Status.Reading.MET : Status.Reading.UNMET,
                "autarkia.direction.storage.free", free.getAsInt(), wanted);
    }

    @Override
    public boolean isWork(Project project, Direction direction, PartyView party) {
        return project instanceof SetUp setUp && setUp.remaining().contains(SetUp.STORE)
                && party.home().isPresent() && BaseLine.inArea(party, setUp.near())
                || project instanceof GrowBuilding grow && grow.station().equals(SetUp.STORE.itemId());
    }

    @Override
    public PartyProject post(Direction direction, PartyView party, double priority) {
        Optional<Growth> growth = party.growth(SetUp.STORE);
        if (growth.isPresent()) {
            return new GrowBuilding(growth.get().structure(), growth.get().variants(), SetUp.STORE.itemId(),
                    Math.max(priority, Direction.BUILDING));
        }
        return new SetUp(List.of(SetUp.STORE), party.spot().orElseThrow(), Math.max(priority, Direction.BUILDING));
    }
}
