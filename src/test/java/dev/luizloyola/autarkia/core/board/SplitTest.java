package dev.luizloyola.autarkia.core.board;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * {@link EvenSplit}'s formula: split what's outstanding evenly across whoever is free, keep the
 * share inside what one trip is worth, and never send anyone for more than is actually left.
 */
class SplitTest {

    @Test
    void oneStackAcrossFourIsFourTrips() {
        assertEquals(16, EvenSplit.INSTANCE.tripFor(64, 4));
    }

    @Test
    void oneStackAcrossEightStillGoesToFour() {
        assertEquals(16, EvenSplit.INSTANCE.tripFor(64, 8),
                "MIN_TRIP is what stops eight people fetching eight items each — the other four "
                        + "are offered nothing and go do something else");
    }

    @Test
    void aBigJobIsCappedByWhatIsWorthCarrying() {
        assertEquals(64, EvenSplit.INSTANCE.tripFor(512, 4));
    }

    @Test
    void aSmallJobStaysOnePersons() {
        assertEquals(16, EvenSplit.INSTANCE.tripFor(16, 4));
    }

    @Test
    void theLastFewAreNotRoundedUpIntoAFullTrip() {
        assertEquals(5, EvenSplit.INSTANCE.tripFor(5, 4),
                "the outer min: asking for sixteen when five are wanted overshoots by eleven");
    }

    @Test
    void nothingOutstandingIsNoTrip() {
        assertEquals(0, EvenSplit.INSTANCE.tripFor(0, 4));
    }
}
