package dev.luizloyola.autarkia.core.board;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.core.inv.Inventory;
import dev.luizloyola.autarkia.core.person.PersonSpecies;
import org.junit.jupiter.api.Test;

/**
 * The one number in 3a that is only correct relative to another one, in different units — so it is
 * asserted against the real profile rather than a literal anybody could re-tune in isolation.
 */
class HaulLineTest {

    @Test
    void aHaulerReachesTheYardBeforeTheSafeguardReachesForTheNearestChest() {
        int haulLine = ClearArea.HAUL_LINE;                       // cargo slots HELD
        int slack = PersonSpecies.PROFILE.fixed().i(ProfileAspect.UNBURDEN_SLACK_SLOTS); // slots still EMPTY

        assertTrue(haulLine + slack < Inventory.ARMOR_START,
                "a settler must cross the haul line while there is still room to spare: if the "
                        + "pack fills first, Unburden takes the wheel and stows at the NEAREST "
                        + "store, so the wood scatters and the yard stays empty — the one thing "
                        + "3a exists to prevent. haulLine=" + haulLine + " slack=" + slack
                        + " storage=" + Inventory.ARMOR_START);
    }

    @Test
    void andWithRoomToSpareForTheKitAndFoodAPackKeeps() {
        int haulLine = ClearArea.HAUL_LINE;
        int slack = PersonSpecies.PROFILE.fixed().i(ProfileAspect.UNBURDEN_SLACK_SLOTS);

        assertTrue(haulLine + slack + 6 <= Inventory.ARMOR_START,
                "six slots of headroom for the tools, torches and food the keep-list holds back — "
                        + "cargo is what crosses the line, and kit does not count toward it");
    }
}
