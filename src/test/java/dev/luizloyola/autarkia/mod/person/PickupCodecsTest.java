package dev.luizloyola.autarkia.mod.person;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import dev.luizloyola.autarkia.core.person.PickupTally;
import org.junit.jupiter.api.Test;

/** Pending pickups ride the save, so a restart neither loses nor repeats them. */
class PickupCodecsTest {

    @Test
    void pendingCountsSurviveASaveAndRestore() {
        PickupTally saved = new PickupTally();
        saved.add("minecraft:leaf_litter", 37, 5_000);
        saved.add("minecraft:oak_log", 12, 5_300);
        JsonElement json = PickupCodecs.TALLY.encodeStart(JsonOps.INSTANCE, saved.snapshot()).getOrThrow();
        PickupTally loaded = new PickupTally();
        loaded.restore(PickupCodecs.TALLY.parse(JsonOps.INSTANCE, json).getOrThrow());

        assertEquals(saved.snapshot(), loaded.snapshot());
        long closes = 5_000 + PickupTally.WINDOW_TICKS;
        assertEquals("picked up 37×minecraft:leaf_litter, 12×minecraft:oak_log", loaded.due(closes));
    }
}
