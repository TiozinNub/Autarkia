package dev.luizloyola.autarkia.core.direction;

import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.autarkia.core.board.Explore;
import dev.luizloyola.autarkia.core.board.PartyProject;
import dev.luizloyola.autarkia.core.board.Project;
import java.util.Optional;

/**
 * {@code home}: the party has a HOME. The one line with work to post while it has none — an
 * {@link Explore} that finds and claims one. An operator's {@code home set} meets it, and the beat
 * withdraws the search as it does any work a met line no longer needs.
 */
public final class HomeLine implements DirectionLine {

    public static final HomeLine INSTANCE = new HomeLine();

    private HomeLine() {
    }

    @Override
    public String id() {
        return "home";
    }

    @Override
    public Optional<ItemSpec> seeks() {
        return Optional.empty();
    }

    @Override
    public Status judge(Direction direction, PartyView party) {
        if (party.home().isPresent()) {
            return Status.of(Status.Reading.MET, "autarkia.direction.home.claimed");
        }
        if (!HomeKnob.EXPLORE.b()) {
            return Status.of(Status.Reading.WAITING, "autarkia.direction.home.off");
        }
        return Status.of(Status.Reading.UNMET, "autarkia.direction.home.searching");
    }

    @Override
    public boolean isWork(Project project, Direction direction, PartyView party) {
        return project instanceof Explore explore && explore.party().equals(party.party());
    }

    @Override
    public PartyProject post(Direction direction, PartyView party, double priority) {
        return new Explore(party.party(), priority);
    }
}
