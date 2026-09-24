package dev.luizloyola.autarkia.core.direction;

import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.autarkia.core.board.CarrySplit;
import dev.luizloyola.autarkia.core.board.Gather;
import dev.luizloyola.autarkia.core.board.PartyProject;
import dev.luizloyola.autarkia.core.board.Project;
import dev.luizloyola.autarkia.core.board.Stock;
import java.util.Optional;
import java.util.OptionalInt;

/** {@code wood}: HOME's stores hold at least {@code count} logs. */
public final class WoodLine implements DirectionLine {

    public static final WoodLine INSTANCE = new WoodLine();

    private WoodLine() {
    }

    @Override
    public String id() {
        return "wood";
    }

    @Override
    public Optional<ItemSpec> seeks() {
        return Optional.of(Stock.LOGS);
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
        OptionalInt stored = party.storedAtHome(Stock.LOGS);
        if (stored.isEmpty()) {
            return Status.UNREAD;
        }
        return Status.of(stored.getAsInt() >= direction.count() ? Status.Reading.MET : Status.Reading.UNMET,
                "autarkia.direction.wood.stored", stored.getAsInt(), direction.count());
    }

    @Override
    public boolean isWork(Project project, Direction direction, PartyView party) {
        return project instanceof Gather gather && gather.spec() == Stock.LOGS
                && party.home().map(home -> home.yard().equals(gather.yard())).orElse(false);
    }

    @Override
    public PartyProject post(Direction direction, PartyView party, double priority) {
        return new Gather(Stock.LOGS, direction.count(), party.home().orElseThrow().yard(), priority,
                party.party(), CarrySplit.INSTANCE);
    }
}
