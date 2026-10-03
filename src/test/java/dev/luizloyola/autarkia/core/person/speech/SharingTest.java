package dev.luizloyola.autarkia.core.person.speech;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.luizloyola.anima.core.social.speech.Handover;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Luiz's rule, 2026-10-02, worked through his own examples. */
class SharingTest {

    private static final double QUARTER = 0.25;

    private static Sharing.Food steaks(int n) {
        return new Sharing.Food("minecraft:cooked_beef", n, 8);
    }

    private static Sharing.Food bread(int n) {
        return new Sharing.Food("minecraft:bread", n, 5);
    }

    private static List<Handover.Item> stranger(Sharing.Food... carried) {
        return Sharing.share(List.of(carried), false, QUARTER);
    }

    @Test
    @DisplayName("a stranger, six steaks (48): the limit is 12, and the second steak takes it over")
    void sixSteaks() {
        assertEquals(List.of(new Handover.Item("minecraft:cooked_beef", 2)), stranger(steaks(6)));
    }

    @Test
    @DisplayName("a stranger is capped at 20 points — and the third steak, to 24, is fine")
    void cappedAtTwenty() {
        assertEquals(List.of(new Handover.Item("minecraft:cooked_beef", 3)), stranger(steaks(10)));
    }

    @Test
    @DisplayName("no surplus beyond a full bar, nothing for a stranger")
    void noSurplus() {
        assertEquals(List.of(), stranger(steaks(2)), "16 points");
        assertEquals(List.of(), stranger(bread(4)), "20 points is the bar itself");
    }

    @Test
    @DisplayName("at least one item, whatever its size")
    void atLeastOne() {
        // 24 points: a surplus, and a quarter of it is 6 — less than one steak.
        assertEquals(List.of(new Handover.Item("minecraft:cooked_beef", 1)), stranger(steaks(3)));
    }

    @Test
    @DisplayName("the kind carried most of goes first")
    void mostCarriedFirst() {
        // 66 points, limit 16.5: bread at 5, 10, 15, 20.
        assertEquals(List.of(new Handover.Item("minecraft:bread", 4)), stranger(steaks(2), bread(10)));
    }

    @Test
    @DisplayName("a colleague gets half, from any amount")
    void colleagueHalf() {
        assertEquals(List.of(new Handover.Item("minecraft:bread", 3)),
                Sharing.share(List.of(bread(5)), true, QUARTER), "25 points, half is 12.5");
        assertEquals(List.of(new Handover.Item("minecraft:cooked_beef", 1)),
                Sharing.share(List.of(steaks(2)), true, QUARTER), "16 points: no surplus asked");
        assertEquals(List.of(), Sharing.share(List.of(), true, QUARTER), "nothing carried, nothing given");
    }
}
