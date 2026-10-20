package dev.luizloyola.autarkia.core.direction;

import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.autarkia.core.board.PartyProject;
import dev.luizloyola.autarkia.core.board.Project;
import dev.luizloyola.autarkia.core.board.SiteBuilding;
import dev.luizloyola.autarkia.core.builder.Structure;
import java.util.Map;
import java.util.Optional;

/**
 * {@code shelter}: somewhere near HOME to spend the night, from the first one (Luiz, 2026-10-20:
 * right after HOME is chosen and claimed, a small dirt hut with a door). Met by any building of the
 * party's that stands with a door, the house included, or by a shelter a member remembers within
 * {@link dev.luizloyola.anima.core.brain.task.Refuge#NEAR_HOME} of HOME; until then it posts the
 * hut. The hut is kept once the house stands.
 */
public final class ShelterLine implements DirectionLine {

    public static final ShelterLine INSTANCE = new ShelterLine();

    public static final String BLUEPRINT = "autarkia:dirt_hut";

    private ShelterLine() {
    }

    @Override
    public String id() {
        return "shelter";
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
        if (party.shelterStands()) {
            return Status.of(Status.Reading.MET, "autarkia.direction.shelter.stands");
        }
        if (party.shelterNearHome()) {
            return Status.of(Status.Reading.MET, "autarkia.direction.shelter.known");
        }
        for (Structure structure : party.structures()) {
            if (structure.blueprint().equals(BLUEPRINT) && structure.phase() != Structure.Phase.REFUSED) {
                return Status.of(Status.Reading.WAITING, "autarkia.direction.shelter.going_up");
            }
        }
        return Status.of(Status.Reading.UNMET, "autarkia.direction.shelter.none");
    }

    /**
     * The ask for a site only. The hut's own clearing, levelling and build are its building's, so a
     * shelter met another way (one remembered near HOME) does not withdraw a hut half built.
     */
    @Override
    public boolean isWork(Project project, Direction direction, PartyView party) {
        return project instanceof SiteBuilding site && site.blueprint().equals(BLUEPRINT);
    }

    @Override
    public PartyProject post(Direction direction, PartyView party, double priority) {
        return new SiteBuilding(BLUEPRINT, Map.of(), priority);
    }
}
