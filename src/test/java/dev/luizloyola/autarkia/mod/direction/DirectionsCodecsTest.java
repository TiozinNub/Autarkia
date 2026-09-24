package dev.luizloyola.autarkia.mod.direction;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import dev.luizloyola.anima.core.brain.knowledge.Region;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.autarkia.core.direction.DirectionId;
import dev.luizloyola.autarkia.core.direction.Home;
import dev.luizloyola.autarkia.mod.direction.DirectionsCodecs.PartyRow;
import dev.luizloyola.autarkia.mod.direction.DirectionsCodecs.PersonRow;
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
                Optional.of(new Home(new Region(new Pos(-16, 48, -16), new Pos(16, 112, 16)),
                        new Pos(0, 64, 0), true)));
        assertEquals(row, roundTrip(DirectionsCodecs.PARTY_ROW, row));
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
