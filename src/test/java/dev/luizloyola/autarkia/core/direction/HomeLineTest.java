package dev.luizloyola.autarkia.core.direction;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.core.social.PartyId;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** A party with no HOME reads as searching while its search goes on, and as stranded once it ends. */
class HomeLineTest {

    private static final Direction HOME = new Direction(new DirectionId("test:wood", "home"), 0, null);

    private static PartyView searching(Optional<HomeSearch.Phase> phase) {
        return new PartyView() {
            @Override
            public PartyId party() {
                return PartyId.of(new UUID(6, 6));
            }

            @Override
            public Optional<Home> home() {
                return Optional.empty();
            }

            @Override
            public Optional<HomeSearch.Phase> homeSearch() {
                return phase;
            }

            @Override
            public int members() {
                return 1;
            }

            @Override
            public OptionalInt storedAtHome(ItemSpec spec) {
                return OptionalInt.empty();
            }

            @Override
            public OptionalInt foodAtHome() {
                return OptionalInt.empty();
            }

            @Override
            public boolean hasAtHome(PoiKind kind) {
                return false;
            }

            @Override
            public OptionalInt freeSlotsAtHome() {
                return OptionalInt.empty();
            }
        };
    }

    @Test
    void aSearchUnderWayIsSearching() {
        for (Optional<HomeSearch.Phase> phase : List.of(Optional.<HomeSearch.Phase>empty(),
                Optional.of(HomeSearch.Phase.WALK))) {
            assertEquals(Status.of(Status.Reading.UNMET, "autarkia.direction.home.searching"),
                    HomeLine.INSTANCE.judge(HOME, searching(phase)), phase.toString());
        }
    }

    @Test
    void aStrandedSearchSaysItFoundNowhere() {
        assertEquals(Status.of(Status.Reading.UNMET, "autarkia.direction.home.stranded"),
                HomeLine.INSTANCE.judge(HOME, searching(Optional.of(HomeSearch.Phase.STRANDED))));
    }
}
