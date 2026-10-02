package dev.luizloyola.autarkia.mod.direction;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.luizloyola.anima.core.brain.knowledge.Region;
import dev.luizloyola.anima.mod.territory.TerritoryData;
import dev.luizloyola.autarkia.core.direction.DirectionId;
import dev.luizloyola.autarkia.core.direction.Home;
import dev.luizloyola.autarkia.core.direction.PartyProgress;
import dev.luizloyola.autarkia.mod.board.PartyBoardCodecs;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;

/**
 * How the Directions store writes itself down, apart from {@link DirectionsData} so it round-trips
 * in a plain unit test. A row that stops decoding is a party losing its climb, which
 * {@code StoreGuard}'s count catches at boot and this catches before the build ships.
 */
public final class DirectionsCodecs {

    private DirectionsCodecs() {
    }

    public static final Codec<DirectionId> DIRECTION_ID = RecordCodecBuilder.create(id -> id.group(
            Codec.STRING.fieldOf("node").forGetter(DirectionId::node),
            Codec.STRING.fieldOf("line").forGetter(DirectionId::line)
    ).apply(id, DirectionId::new));

    /**
     * HOME as the file holds it. {@code plot} is read and never written: a save from before the area
     * (2026-10-01) kept a square, whose chunks the party is given once the server is up. A save's
     * {@code yard}, from before HOME lost it the same day, is ignored.
     */
    public record SavedHome(Home home, Optional<Region> plot) {
    }

    public static final Codec<SavedHome> HOME = RecordCodecBuilder.create(home -> home.group(
            TerritoryData.CHUNKS.optionalFieldOf("felled_chunks", Set.of())
                    .forGetter(saved -> saved.home().felled()),
            TerritoryData.CHUNKS.optionalFieldOf("cleared_chunks", Set.of())
                    .forGetter(saved -> saved.home().cleared()),
            PartyBoardCodecs.REGION.optionalFieldOf("plot").forGetter(saved -> Optional.empty())
    ).apply(home, (felled, cleared, plot) -> new SavedHome(
            new Home(new TreeSet<>(felled), new TreeSet<>(cleared)), plot)));

    /** One party's climb. Node ids travel as strings: a node the table no longer names stays reached. */
    public record PartyRow(UUID party, List<String> reached, List<DirectionId> checkpoints,
                           Optional<SavedHome> home, List<SavedStall> stalls) {

        public PartyRow(UUID party, List<String> reached, List<DirectionId> checkpoints,
                        Optional<SavedHome> home) {
            this(party, reached, checkpoints, home, List.of());
        }
    }

    /** One line's stall: a restart neither forgives a stuck line nor makes it wait again from nothing. */
    public record SavedStall(DirectionId direction, PartyProgress.Stall stall) {
    }

    public static final Codec<SavedStall> STALL = RecordCodecBuilder.create(row -> row.group(
            DIRECTION_ID.fieldOf("direction").forGetter(SavedStall::direction),
            Codec.INT.fieldOf("last").forGetter(saved -> saved.stall().last()),
            Codec.INT.fieldOf("beats").forGetter(saved -> saved.stall().beats())
    ).apply(row, (direction, last, beats) -> new SavedStall(direction, new PartyProgress.Stall(last, beats))));

    /** What one person has reached, wherever they have been. */
    public record PersonRow(UUID agent, List<String> reached) {
    }

    public static final Codec<PartyRow> PARTY_ROW = RecordCodecBuilder.create(row -> row.group(
            UUIDUtil.CODEC.fieldOf("party").forGetter(PartyRow::party),
            Codec.STRING.listOf().optionalFieldOf("reached", List.of()).forGetter(PartyRow::reached),
            DIRECTION_ID.listOf().optionalFieldOf("checkpoints", List.of())
                    .forGetter(PartyRow::checkpoints),
            HOME.optionalFieldOf("home").forGetter(PartyRow::home),
            STALL.listOf().optionalFieldOf("stalls", List.of()).forGetter(PartyRow::stalls)
    ).apply(row, PartyRow::new));

    public static final Codec<PersonRow> PERSON_ROW = RecordCodecBuilder.create(row -> row.group(
            UUIDUtil.CODEC.fieldOf("agent").forGetter(PersonRow::agent),
            Codec.STRING.listOf().fieldOf("reached").forGetter(PersonRow::reached)
    ).apply(row, PersonRow::new));
}
