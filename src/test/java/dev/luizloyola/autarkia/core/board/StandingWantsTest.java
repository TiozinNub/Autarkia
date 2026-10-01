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
    void theFourToolsAreToolUpsNotThese() {
        List<ItemCall> calls = StandingWants.settlerDefaults().reserved();

        for (String tool : List.of("sword", "pickaxe", "axe", "shovel")) {
            assertEquals(-1, rankOf(calls, "minecraft:stone_" + tool));
        }
    }

    @Test
    void torchesOutrankTheSecondTier() {
        List<ItemCall> calls = StandingWants.settlerDefaults().reserved();

        assertTrue(rankOf(calls, "minecraft:torch") < rankOf(calls, "minecraft:shears"));
    }

    @Test
    void spentThingsAreKeptByTheStackAndToolsAreNot() {
        List<ItemCall> calls = StandingWants.settlerDefaults().reserved();
        ItemCall torches = calls.get(rankOf(calls, "minecraft:torch"));

        assertTrue(torches.count() > 1,
                "torches are spent, not carried — one is not a supply");
    }

    /** Last, so it is the first thing a full pack gives up; kept, never sought. */
    @Test
    void aStackToBridgeWithIsKeptBelowEveryTool() {
        Stock.layableBy(id -> id.equals("minecraft:dirt"));
        try {
            List<ItemCall> calls = StandingWants.settlerDefaults().reserved();
            assertEquals(calls.size() - 1, rankOf(calls, "minecraft:dirt"));
            assertEquals(16, calls.get(calls.size() - 1).count());
        } finally {
            Stock.layableBy(id -> false);
        }
    }
}
