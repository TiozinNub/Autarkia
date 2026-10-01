package dev.luizloyola.autarkia.core.direction;

import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.autarkia.core.board.Build;
import dev.luizloyola.autarkia.core.board.PartyProject;
import dev.luizloyola.autarkia.core.board.Project;
import dev.luizloyola.autarkia.core.board.SiteBuilding;
import dev.luizloyola.autarkia.core.builder.Structure;
import java.util.Map;
import java.util.Optional;

/**
 * {@code house}: a house stands with a crafting table and storage, to replace the starter blocks
 * (docs/superpowers/specs/2026-10-01-house-site-design.md, decisions 6–9). Until one is sited the
 * line posts the choice of where; then it waits — for the ground to be cleared, for the pad to be
 * levelled, for the builder. A refused site is no house, and the next choice keeps off its pad.
 * The line is met once the house stands.
 */
public final class HouseLine implements DirectionLine {

    public static final HouseLine INSTANCE = new HouseLine();

    /** Decision 9: the basic house, with the crafting table and the double chest of {@code base=lv1}. */
    public static final String BLUEPRINT = "autarkia:basic_wooden_house";

    /** The least selection that meets the need; beds wait for wool (builder ruling 11). */
    public static final Map<String, String> VARIANTS = Map.of("base", "lv1", "beds", "none", "attic", "none");

    private HouseLine() {
    }

    @Override
    public String id() {
        return "house";
    }

    @Override
    public Optional<ItemSpec> seeks() {
        return Optional.empty();
    }

    @Override
    public Status judge(Direction direction, PartyView party) {
        if (party.home().isEmpty() || party.area().isEmpty()) {
            return Status.NO_HOME;
        }
        if (!party.baseReady()) {
            return Status.NO_BASE;
        }
        for (Structure structure : party.structures()) {
            if (!structure.blueprint().equals(BLUEPRINT)) {
                continue;
            }
            switch (structure.phase()) {
                case SITED -> {
                    return Status.of(Status.Reading.WAITING, "autarkia.direction.house.sited");
                }
                case LEVELLING -> {
                    return Status.of(Status.Reading.WAITING, "autarkia.direction.house.levelling");
                }
                case LEVELLED -> {
                    return Status.of(Status.Reading.WAITING, "autarkia.direction.house.levelled");
                }
                case BUILDING -> {
                    return Status.of(Status.Reading.WAITING, "autarkia.direction.house.building");
                }
                case BUILT -> {
                    return Status.of(Status.Reading.MET, "autarkia.direction.house.built");
                }
                case REFUSED -> {
                }
            }
        }
        return Status.of(Status.Reading.UNMET, "autarkia.direction.house.none");
    }

    @Override
    public boolean isWork(Project project, Direction direction, PartyView party) {
        return project instanceof SiteBuilding site && site.blueprint().equals(BLUEPRINT)
                || project instanceof Build build && build.name().equals(BLUEPRINT);
    }

    @Override
    public PartyProject post(Direction direction, PartyView party, double priority) {
        return new SiteBuilding(BLUEPRINT, VARIANTS, priority);
    }
}
