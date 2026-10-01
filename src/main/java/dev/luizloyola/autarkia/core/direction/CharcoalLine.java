package dev.luizloyola.autarkia.core.direction;

import dev.luizloyola.anima.core.craft.Furnace;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.autarkia.core.board.Fire;
import dev.luizloyola.autarkia.core.board.PartyProject;
import dev.luizloyola.autarkia.core.board.Project;
import dev.luizloyola.autarkia.core.board.SetUp;
import dev.luizloyola.autarkia.core.board.Stock;
import dev.luizloyola.autarkia.core.board.Tend;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/**
 * {@code charcoal}: HOME's stores hold at least {@code count} charcoal a member (decision 20) — a
 * kit's 64 torches is 16. Waits for the base. With no furnace at HOME it posts one; then a load of
 * logs for the shortfall, at most a furnace's stack. While the furnace smelts it waits: coming back
 * for the charcoal is a {@link Tend}'s, and the line judges again once it is in the chest.
 */
public final class CharcoalLine implements DirectionLine {

    public static final CharcoalLine INSTANCE = new CharcoalLine();

    public static final ItemSpec CHARCOAL = ItemSpec.anyOf(Set.of("minecraft:charcoal"));

    private CharcoalLine() {
    }

    @Override
    public String id() {
        return "charcoal";
    }

    @Override
    public Optional<ItemSpec> seeks() {
        return Optional.of(CHARCOAL);
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
        OptionalInt stored = party.storedAtHome(CHARCOAL);
        if (stored.isEmpty()) {
            return Status.UNREAD;
        }
        int wanted = wanted(direction, party);
        if (stored.getAsInt() < wanted && party.runningAtHome(Furnace.POI)) {
            return Status.of(Status.Reading.WAITING, "autarkia.direction.charcoal.smelting",
                    stored.getAsInt(), wanted);
        }
        return Status.of(stored.getAsInt() >= wanted ? Status.Reading.MET : Status.Reading.UNMET,
                "autarkia.direction.charcoal.stored", stored.getAsInt(), wanted);
    }

    @Override
    public boolean isWork(Project project, Direction direction, PartyView party) {
        if (party.home().isEmpty()) {
            return false;
        }
        if (project instanceof SetUp setUp) {
            return setUp.near().equals(party.home().get().yard()) && setUp.remaining().contains(SetUp.FURNACE);
        }
        Optional<dev.luizloyola.anima.core.brain.sense.Pos> furnace = party.placeAtHome(Furnace.POI);
        return furnace.isPresent() && (project instanceof Fire fire && fire.at().equals(furnace.get())
                || project instanceof Tend tend && tend.at().equals(furnace.get()));
    }

    @Override
    public PartyProject post(Direction direction, PartyView party, double priority) {
        Optional<dev.luizloyola.anima.core.brain.sense.Pos> furnace = party.placeAtHome(Furnace.POI);
        if (furnace.isEmpty()) {
            return new SetUp(List.of(SetUp.FURNACE), party.home().orElseThrow().yard(), priority);
        }
        int shortfall = wanted(direction, party) - party.storedAtHome(CHARCOAL).orElse(0);
        return new Fire(furnace.get(), Stock.LOGS, Math.min(64, Math.max(1, shortfall)), Stock.PLANKS, priority);
    }

    private static int wanted(Direction direction, PartyView party) {
        return direction.count() * party.members();
    }
}
