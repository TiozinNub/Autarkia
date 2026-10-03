package dev.luizloyola.autarkia.mod.builder;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class MovingInTest {

    /** Clara's: 16 stacks outside, five empty chests grown in the house, 12 slots kept free. */
    @Test
    void aStoreLeftOutsideFoldsInOnceTheHouseHasRoomForIt() {
        assertEquals(List.of(0), MovingIn.folding(List.of(16), 5 * 27 - 12));
    }

    @Test
    void aStoreTheHouseHasNoRoomForStaysOut() {
        assertEquals(List.of(), MovingIn.folding(List.of(16), 15));
    }

    @Test
    void storesFoldInOrderWhileTheRoomLasts() {
        assertEquals(List.of(0, 2), MovingIn.folding(List.of(10, 20, 5), 18));
    }
}
