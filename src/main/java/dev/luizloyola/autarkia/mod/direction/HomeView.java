package dev.luizloyola.autarkia.mod.direction;

import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.Food;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.core.social.PartyId;
import dev.luizloyola.anima.core.social.PlaceRow;
import dev.luizloyola.anima.core.store.Store;
import dev.luizloyola.anima.core.territory.ChunkKey;
import dev.luizloyola.anima.mod.social.PlacesData;
import dev.luizloyola.anima.mod.territory.Territories;
import dev.luizloyola.autarkia.compat.inv.StoreContents;
import dev.luizloyola.autarkia.core.config.AutarkiaConfig;
import dev.luizloyola.autarkia.core.direction.Home;
import dev.luizloyola.autarkia.core.direction.PartyProgress;
import dev.luizloyola.autarkia.core.direction.PartyView;
import java.util.HashSet;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;

/**
 * A party as its Directions may see it. HOME's stores are the party's own claimed chests on the plot
 * or near enough its yard to count — the same {@code stores.found_radius} a gather's own yard uses,
 * or a gather's chest just off the plot would never count toward the line that posted it.
 *
 * <p>Overworld only: a claimed place carries no dimension yet, and every settlement so far is there.
 */
final class HomeView implements PartyView {

    private final MinecraftServer server;
    private final PartyId party;
    private final PartyProgress progress;
    private final int members;

    HomeView(MinecraftServer server, PartyId party, PartyProgress progress, int members) {
        this.server = server;
        this.party = party;
        this.progress = progress;
        this.members = members;
    }

    @Override
    public PartyId party() {
        return party;
    }

    @Override
    public Optional<Home> home() {
        return Optional.ofNullable(progress.home());
    }

    @Override
    public SortedSet<ChunkKey> area() {
        SortedSet<ChunkKey> overworld = new TreeSet<>();
        for (ChunkKey chunk : Territories.of(server).area(party)) {
            if (chunk.dimension().equals(ChunkKey.OVERWORLD)) {
                overworld.add(chunk);
            }
        }
        return overworld;
    }

    @Override
    public int members() {
        return members;
    }

    @Override
    public OptionalInt storedAtHome(ItemSpec spec) {
        return readHome(spec::matches, StoreContents.Reading::matching);
    }

    @Override
    public OptionalInt foodAtHome() {
        return readHome(Food.SPEC::matches, StoreContents.Reading::nutrition);
    }

    @Override
    public OptionalInt freeSlotsAtHome() {
        return readHome(id -> false, StoreContents.Reading::free);
    }

    @Override
    public boolean hasAtHome(PoiKind kind) {
        return placeAtHome(kind).isPresent();
    }

    @Override
    public java.util.Optional<dev.luizloyola.anima.core.brain.sense.Pos> placeAtHome(PoiKind kind) {
        Home home = progress.home();
        if (home == null) {
            return java.util.Optional.empty();
        }
        SortedSet<ChunkKey> area = area();
        for (PlaceRow row : PlacesData.get(server).places().rows()) {
            if (row.kind().equals(kind) && party.equals(row.party()) && atHome(home, area, row.at())) {
                return java.util.Optional.of(row.at());
            }
        }
        return java.util.Optional.empty();
    }

    @Override
    public boolean runningAtHome(PoiKind kind) {
        return placeAtHome(kind).map(at -> PlacesData.get(server).places().processes().keySet().stream()
                .anyMatch(row -> row.kind().equals(kind) && row.at().equals(at))).orElse(false);
    }

    /** One number summed over HOME's stores, or empty when any of them could not be read. */
    private OptionalInt readHome(Predicate<String> ids, ToIntFunction<StoreContents.Reading> part) {
        Home home = progress.home();
        if (home == null) {
            return OptionalInt.empty();
        }
        SortedSet<ChunkKey> area = area();
        Set<BlockPos> counted = new HashSet<>();
        int total = 0;
        for (PlaceRow row : PlacesData.get(server).places().rows()) {
            if (!row.kind().equals(Store.POI) || !party.equals(row.party()) || !atHome(home, area, row.at())) {
                continue;
            }
            Optional<StoreContents.Reading> read = StoreContents.read(server.overworld(), row.at(), ids,
                    counted);
            if (read.isEmpty()) {
                return OptionalInt.empty();
            }
            total += part.applyAsInt(read.get());
        }
        return OptionalInt.of(total);
    }

    /**
     * In the area, or near enough the yard to count — the radius a gather's own yard uses. The
     * starter base needs no area, so a chest just past its edge is still HOME's.
     */
    private static boolean atHome(Home home, Set<ChunkKey> area, Pos at) {
        return area.contains(ChunkKey.at(ChunkKey.OVERWORLD, at.x(), at.z()))
                || Store.distance(at, home.yard()) <= AutarkiaConfig.PERSON.i(ProfileAspect.STORES_FOUND_RADIUS);
    }
}
