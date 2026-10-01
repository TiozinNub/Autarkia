package dev.luizloyola.autarkia.mod.direction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import dev.luizloyola.anima.core.brain.knowledge.Region;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.territory.ChunkKey;
import dev.luizloyola.autarkia.core.direction.DirectionId;
import dev.luizloyola.autarkia.core.direction.Home;
import dev.luizloyola.autarkia.mod.board.PartyBoardCodecs;
import dev.luizloyola.autarkia.mod.direction.DirectionsCodecs.PartyRow;
import dev.luizloyola.autarkia.mod.direction.DirectionsCodecs.PersonRow;
import dev.luizloyola.autarkia.mod.direction.DirectionsCodecs.SavedHome;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** A party's climb and a person's reached nodes come back as they were written. */
class DirectionsCodecsTest {

    private static <T> T roundTrip(Codec<T> codec, T value) {
        return codec.parse(JsonOps.INSTANCE, codec.encodeStart(JsonOps.INSTANCE, value).getOrThrow())
                .getOrThrow();
    }

    @Test
    void aPartyRowKeepsItsNodesCheckpointsAndHome() {
        PartyRow row = new PartyRow(new UUID(1, 2), List.of("autarkia:wood", "autarkia:stone"),
                List.of(new DirectionId("autarkia:wood", "wood"), new DirectionId("autarkia:wood", "area")),
                Optional.of(new SavedHome(Home.fresh()
                        .withCleared(new ChunkKey(ChunkKey.OVERWORLD, -1, 0))
                        .withCleared(new ChunkKey(ChunkKey.OVERWORLD, 0, 0)), Optional.empty())));
        assertEquals(row, roundTrip(DirectionsCodecs.PARTY_ROW, row));
    }

    /** A save from before the area kept a square plot: it is read once, for the server to claim. */
    @Test
    void aHomeSavedAsAPlotHandsOverThePlotAndForgetsItsYard() {
        Region plot = new Region(new Pos(-8, 48, -8), new Pos(8, 112, 8));
        JsonObject old = new JsonObject();
        old.add("yard", PartyBoardCodecs.POS.encodeStart(JsonOps.INSTANCE, new Pos(0, 64, 0)).getOrThrow());
        old.addProperty("cleared", true);
        old.add("plot", PartyBoardCodecs.REGION.encodeStart(JsonOps.INSTANCE, plot).getOrThrow());
        SavedHome saved = DirectionsCodecs.HOME.parse(JsonOps.INSTANCE, old).getOrThrow();
        assertTrue(saved.home().cleared().isEmpty(), "a square's clearing is not a chunk's");
        assertEquals(plot, saved.plot().orElseThrow());
        JsonObject written = DirectionsCodecs.HOME.encodeStart(JsonOps.INSTANCE, saved).getOrThrow()
                .getAsJsonObject();
        assertFalse(written.has("plot"), "and it is never written again");
    }

    @Test
    void aPartyWithNoHomeStaysWithout() {
        PartyRow row = new PartyRow(new UUID(3, 4), List.of("autarkia:stone"), List.of(), Optional.empty());
        assertEquals(row, roundTrip(DirectionsCodecs.PARTY_ROW, row));
    }

    @Test
    void aPersonRowKeepsWhatTheyReached() {
        PersonRow row = new PersonRow(new UUID(5, 6), List.of("autarkia:stone", "mypack:fishing"));
        assertEquals(row, roundTrip(DirectionsCodecs.PERSON_ROW, row));
    }
}
