package dev.luizloyola.autarkia.core.direction;

import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.autarkia.core.board.Build;
import dev.luizloyola.autarkia.core.board.GrowBuilding;
import dev.luizloyola.autarkia.core.board.PartyProject;
import dev.luizloyola.autarkia.core.board.Project;
import dev.luizloyola.autarkia.core.board.SetUp;
import dev.luizloyola.autarkia.core.board.SiteBuilding;
import dev.luizloyola.autarkia.core.builder.Growth;
import dev.luizloyola.autarkia.core.builder.Structure;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code house}: a house stands with a crafting table and storage, to replace the starter blocks
 * (docs/superpowers/specs/2026-10-01-house-site-design.md, decisions 6–9). Until one is sited the
 * line posts the choice of where; then it waits — for the ground to be cleared, for the pad to be
 * levelled, for the builder. A refused site is no house, and the next choice keeps off its pad.
 * The line is met once the house stands and holds every kind of station the party keeps at HOME.
 *
 * <p><b>The house wants an upgrade</b> (2026-10-02): a station kept outside it — the starter furnace
 * — while the house can grow into one ({@code base=lv3}) leaves the line unmet, and it posts the
 * growth. Once grown, the house's own replaces the one outside.
 */
public final class HouseLine implements DirectionLine {

    public static final HouseLine INSTANCE = new HouseLine();

    /** Decision 9: the basic house, with the crafting table and the double chest of {@code base=lv1}. */
    public static final String BLUEPRINT = "autarkia:basic_wooden_house";

    /** The least selection that meets the need; beds wait for wool (builder ruling 11). */
    public static final Map<String, String> VARIANTS = Map.of("base", "lv1", "beds", "none", "attic", "none");

    /** What a house may hold that the party might keep outside it. */
    private static final List<SetUp.Station> STATIONS = List.of(SetUp.WORKBENCH, SetUp.STORE, SetUp.FURNACE);

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
        // A house that stands is judged first: one more sited after it is not what the line waits on.
        List<Structure> houses = party.structures().stream().filter(s -> s.blueprint().equals(BLUEPRINT))
                .sorted(java.util.Comparator.comparing(s -> !s.stands())).toList();
        for (Structure structure : houses) {
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
                case GROWING -> {
                    return Status.of(Status.Reading.WAITING, "autarkia.direction.house.growing");
                }
                case BUILT -> {
                    Optional<SetUp.Station> outside = outside(party);
                    return outside.isPresent()
                            ? Status.of(Status.Reading.UNMET, "autarkia.direction.house.grow."
                                    + outside.get().itemId().substring(outside.get().itemId().indexOf(':') + 1))
                            : Status.of(Status.Reading.MET, "autarkia.direction.house.built");
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
                || project instanceof Build build && build.name().equals(BLUEPRINT)
                || project instanceof GrowBuilding grow
                && outside(party).filter(s -> s.itemId().equals(grow.station())).isPresent();
    }

    @Override
    public PartyProject post(Direction direction, PartyView party, double priority) {
        Optional<SetUp.Station> outside = outside(party);
        if (outside.isPresent()) {
            Growth growth = party.growth(outside.get()).orElseThrow();
            return new GrowBuilding(growth.structure(), growth.variants(), outside.get().itemId(), priority);
        }
        return new SiteBuilding(BLUEPRINT, VARIANTS, priority);
    }

    /**
     * A station the party keeps at HOME with none of its kind inside its buildings, which a building
     * of the party's can grow into.
     */
    private static Optional<SetUp.Station> outside(PartyView party) {
        for (SetUp.Station station : STATIONS) {
            if (party.hasAtHome(station.kind()) && !party.housed(station.kind())
                    && party.growth(station).isPresent()) {
                return Optional.of(station);
            }
        }
        return Optional.empty();
    }
}
