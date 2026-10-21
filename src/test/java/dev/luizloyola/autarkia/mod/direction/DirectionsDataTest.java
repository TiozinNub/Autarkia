package dev.luizloyola.autarkia.mod.direction;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.social.PartyId;
import org.junit.jupiter.api.Test;

/** A party that ceased to exist leaves no climb behind (Autarkia #47). */
class DirectionsDataTest {

    @Test
    void aPartyThatEndedTakesItsClimbWithIt() {
        DirectionsData data = new DirectionsData();
        PartyId gone = PartyId.random();
        PartyId staying = PartyId.random();
        data.progress(gone).reach("wood");
        data.progress(staying).reach("wood");
        data.setDirty(false);

        data.forget(gone);

        assertTrue(data.find(gone).isEmpty());
        assertTrue(data.find(staying).isPresent());
        assertTrue(data.isDirty(), "or the climb comes back on the next load");
    }

    @Test
    void forgettingAPartyNeverSeenWritesNothing() {
        DirectionsData data = new DirectionsData();
        data.setDirty(false);
        data.forget(PartyId.random());
        assertFalse(data.isDirty());
    }
}
