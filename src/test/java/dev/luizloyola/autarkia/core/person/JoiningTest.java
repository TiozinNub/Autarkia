package dev.luizloyola.autarkia.core.person;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.autarkia.core.person.Joining.Ask;
import dev.luizloyola.autarkia.core.person.Joining.Mover;
import dev.luizloyola.autarkia.core.person.Joining.Side;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Who moves when two parties group up: the group-up spec's decisions 1 and 5, case by case. */
class JoiningTest {

    private static Side homeless(boolean led, int size) {
        return new Side(true, led, size);
    }

    private static Side settled(int size) {
        return new Side(false, true, size);
    }

    @Test
    void twoSettledPartiesNeverMerge() {
        assertEquals(Mover.NEITHER, Joining.between(settled(1), settled(9)));
        assertEquals(Optional.empty(), Joining.askFor(settled(1), settled(9)));
    }

    @Test
    void theHomelessSideMovesWhateverTheSizes() {
        assertEquals(Mover.SELF, Joining.between(homeless(true, 9), settled(1)));
        assertEquals(Mover.OTHER, Joining.between(settled(1), homeless(true, 9)));
        assertEquals(Optional.of(Ask.JOIN), Joining.askFor(homeless(false, 1), settled(3)));
        assertEquals(Optional.of(Ask.INVITE), Joining.askFor(settled(3), homeless(false, 1)));
    }

    @Test
    void betweenHomelessSidesALedPartyStaysEvenWhenSmaller() {
        assertEquals(Mover.OTHER, Joining.between(homeless(true, 1), homeless(false, 5)));
        assertEquals(Mover.SELF, Joining.between(homeless(false, 5), homeless(true, 1)));
    }

    @Test
    void withNoLeaderOrTwoTheLargerStays() {
        assertEquals(Mover.SELF, Joining.between(homeless(false, 2), homeless(false, 3)));
        assertEquals(Mover.OTHER, Joining.between(homeless(true, 3), homeless(true, 2)));
    }

    @Test
    void atATieWhatWasSaidDecides() {
        Side a = homeless(false, 2);
        Side b = homeless(false, 2);
        assertEquals(Mover.EITHER, Joining.between(a, b));
        assertEquals(Optional.of(Ask.JOIN), Joining.askFor(a, b), "a homeless body asks to join");
        assertTrue(Joining.holds(a, b, Ask.JOIN));
        assertTrue(Joining.holds(a, b, Ask.INVITE));
    }

    @Test
    void anAskStopsHoldingWhenItsWordsNoLongerMatch() {
        // Bram asked to join while homeless, then his party settled before the answer.
        assertTrue(Joining.holds(homeless(false, 1), settled(3), Ask.JOIN));
        assertFalse(Joining.holds(settled(1), settled(3), Ask.JOIN));
        // An invitation to someone who must stay is not one either.
        assertFalse(Joining.holds(homeless(false, 1), settled(3), Ask.INVITE));
    }

    @Test
    void aScoutLeadsOnlyAPartyWithCompanyAndNoLeader() {
        assertFalse(Joining.scoutLeads(1, false), "a loner searching alone leads nobody");
        assertTrue(Joining.scoutLeads(2, false));
        assertFalse(Joining.scoutLeads(2, true), "a player who joined first keeps the lead");
    }
}
