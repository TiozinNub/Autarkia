package dev.luizloyola.autarkia.core.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.inv.ItemCall;
import java.util.List;
import org.junit.jupiter.api.Test;

/** What a settler keeps, and the order it is kept in. */
class StandingWantsTest {

    private static int rankOf(List<ItemCall> calls, String id) {
        for (int i = 0; i < calls.size(); i++) {
            if (calls.get(i).spec().matches(id)) {
                return i;
            }
        }
        return -1;
    }

    @Test
    void itPostsNothingEver() {
        StandingWants wants = StandingWants.settlerDefaults();

        assertTrue(wants.open().isEmpty(),
                "a standing want reserves; it never sends anybody shopping");
        assertFalse(wants.finished(), "and it is never done, because it is a condition");
    }

    @Test
    void theFirstTierOutranksTheSecond() {
        List<ItemCall> calls = StandingWants.settlerDefaults().reserved();

        int axe = rankOf(calls, "minecraft:stone_axe");
        int shears = rankOf(calls, "minecraft:shears");
        assertTrue(axe >= 0, "an axe is declared");
        assertTrue(shears >= 0, "so are the second-tier tools");
        assertTrue(axe < shears, "an axe outranks shears when the pack has to give something up");
    }

    @Test
    void aToolIsKeptOneAtATime() {
        ItemCall axe = StandingWants.settlerDefaults().reserved().stream()
                .filter(call -> call.spec().matches("minecraft:stone_axe"))
                .findFirst().orElseThrow();

        assertEquals(1, axe.count(),
                "one axe is kept and a second is cargo — what makes five shovels resolve");
    }

    @Test
    void aToolIsKeptWhateverItIsMadeOf() {
        List<ItemCall> calls = StandingWants.settlerDefaults().reserved();

        assertTrue(rankOf(calls, "minecraft:iron_pickaxe") >= 0);
        assertTrue(rankOf(calls, "minecraft:wooden_pickaxe") >= 0);
        assertEquals(rankOf(calls, "minecraft:iron_pickaxe"), rankOf(calls, "minecraft:wooden_pickaxe"),
                "one call covers the whole family, so upgrading a tool does not orphan the want");
    }

    @Test
    void spentThingsAreKeptByTheStackAndToolsAreNot() {
        List<ItemCall> calls = StandingWants.settlerDefaults().reserved();
        ItemCall torches = calls.get(rankOf(calls, "minecraft:torch"));

        assertTrue(torches.count() > 1,
                "torches are spent, not carried — one is not a supply");
    }
}
