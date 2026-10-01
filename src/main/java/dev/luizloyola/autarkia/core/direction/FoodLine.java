package dev.luizloyola.autarkia.core.direction;

import dev.luizloyola.anima.core.brain.task.Food;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.autarkia.core.board.CarrySplit;
import dev.luizloyola.autarkia.core.board.Gather;
import dev.luizloyola.autarkia.core.board.PartyProject;
import dev.luizloyola.autarkia.core.board.Project;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * {@code food}: HOME's stores hold food, raw or ready (decision 16), worth at least {@code count} hunger points a member —
 * 32 (four steaks, seven bread) by decision 13 of {@code 2026-09-24-directions-design.md}. Per
 * member, so a party of twenty is not fed by a goal meant for three.
 *
 * <p>The condition is in points and a gather counts items, so the gather this posts asks for what
 * is there plus the shortfall at {@link #POINTS_PER_ITEM} a piece, a berry's or a melon slice's
 * worth. It errs long on anything richer, and the next beat withdraws it once the points are met.
 */
public final class FoodLine implements DirectionLine {

    public static final FoodLine INSTANCE = new FoodLine();

    static final int POINTS_PER_ITEM = 2;

    private FoodLine() {
    }

    @Override
    public String id() {
        return "food";
    }

    @Override
    public Optional<ItemSpec> seeks() {
        return Optional.of(Food.SPEC);
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
        OptionalInt points = party.foodAtHome();
        if (points.isEmpty()) {
            return Status.UNREAD;
        }
        int wanted = wanted(direction, party);
        return Status.of(points.getAsInt() >= wanted ? Status.Reading.MET : Status.Reading.UNMET,
                "autarkia.direction.food.stored", points.getAsInt(), wanted);
    }

    @Override
    public boolean isWork(Project project, Direction direction, PartyView party) {
        return project instanceof Gather gather && gather.spec() == Food.SPEC && party.home().isPresent();
    }

    @Override
    public PartyProject post(Direction direction, PartyView party, double priority) {
        int shortfall = Math.max(0, wanted(direction, party) - party.foodAtHome().orElse(0));
        int target = party.storedAtHome(Food.SPEC).orElse(0)
                + (shortfall + POINTS_PER_ITEM - 1) / POINTS_PER_ITEM;
        return new Gather(Food.SPEC, Math.max(1, target), priority, party.party(), CarrySplit.INSTANCE);
    }

    private static int wanted(Direction direction, PartyView party) {
        return direction.count() * party.members();
    }
}
