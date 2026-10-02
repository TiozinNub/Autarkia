package dev.luizloyola.autarkia.mod.direction;

import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.compat.sense.LevelProbe;
import dev.luizloyola.anima.core.brain.task.EnsureTable;
import dev.luizloyola.anima.core.brain.task.Food;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.core.social.PartyId;
import dev.luizloyola.anima.core.social.PlaceRow;
import dev.luizloyola.anima.core.store.Store;
import dev.luizloyola.anima.core.territory.ChunkKey;
import dev.luizloyola.anima.mod.social.PlacesData;
import dev.luizloyola.anima.mod.territory.Territories;
import dev.luizloyola.autarkia.compat.inv.StoreContents;
import dev.luizloyola.autarkia.core.bp.Footprint;
import dev.luizloyola.autarkia.core.builder.Structure;
import dev.luizloyola.autarkia.core.direction.Home;
import dev.luizloyola.autarkia.core.direction.PartyProgress;
import dev.luizloyola.autarkia.core.direction.PartyView;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * A party as its Directions may see it. HOME's stores are the party's own claimed chests in its
 * area, and nowhere else (home area decision 11): a chest a gatherer makes outside it is a camp's.
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
    public Optional<Pos> spot() {
        if (progress.home() == null) {
            return Optional.empty();
        }
        // Never on a site being built: its base is being moved off it, and a store put back
        // there would be broken again by the build.
        List<Footprint> sites = new ArrayList<>();
        for (Structure structure : structures()) {
            if (structure.phase() != Structure.Phase.BUILT && structure.phase() != Structure.Phase.REFUSED) {
                sites.add(structure.pad());
            }
        }
        SortedSet<ChunkKey> area = area();
        for (PlaceRow row : PlacesData.get(server).places().rows()) {
            if (row.kind().equals(Store.POI) && party.equals(row.party()) && atHome(area, row.at())
                    && !inside(sites, row.at().x(), row.at().z())) {
                return Optional.of(row.at());
            }
        }
        return Home.middle(area).map(chunk -> ground(server, chunk, sites));
    }

    private static boolean inside(List<Footprint> sites, int x, int z) {
        for (Footprint f : sites) {
            if (x >= f.minX() && x <= f.maxX() && z >= f.minZ() && z <= f.maxZ()) {
                return true;
            }
        }
        return false;
    }

    /**
     * The open column nearest a chunk's middle, at the cell above its ground: no leaves over it and
     * no water on it, so a body can stand there. A forest's low canopy left a spot with a block of
     * headroom that nobody could reach, and the base never went down (2026-10-01). The middle
     * itself when nothing in the chunk is open; sea level when the chunk is not loaded, since
     * reading it would load it.
     */
    private static Pos ground(MinecraftServer server, ChunkKey chunk, List<Footprint> sites) {
        net.minecraft.server.level.ServerLevel level = server.overworld();
        int midX = chunk.minBlockX() + 8;
        int midZ = chunk.minBlockZ() + 8;
        if (!level.hasChunk(chunk.x(), chunk.z())) {
            return new Pos(midX, level.getSeaLevel(), midZ);
        }
        Pos best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (int x = chunk.minBlockX(); x <= chunk.maxBlockX(); x++) {
            for (int z = chunk.minBlockZ(); z <= chunk.maxBlockZ(); z++) {
                int distance = (x - midX) * (x - midX) + (z - midZ) * (z - midZ);
                if (distance >= bestDistance || inside(sites, x, z)) {
                    continue;
                }
                int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                boolean open = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) == ground
                        && level.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z) == ground;
                if (open) {
                    best = new Pos(x, ground, z);
                    bestDistance = distance;
                }
            }
        }
        return best != null ? best
                : new Pos(midX, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, midX, midZ), midZ);
    }

    @Override
    public java.util.List<dev.luizloyola.autarkia.core.builder.Structure> structures() {
        return dev.luizloyola.autarkia.mod.builder.StructuresData.get(server).of(party);
    }

    @Override
    public boolean stuck(dev.luizloyola.autarkia.core.direction.DirectionId direction) {
        return progress.stall(direction).map(PartyProgress.Stall::stuck).orElse(false);
    }

    @Override
    public int members() {
        return members;
    }

    @Override
    public OptionalInt storedAtHome(ItemSpec spec) {
        return readHome(spec::matches, StoreContents.Reading::matching, false);
    }

    @Override
    public OptionalInt takeableAtHome(ItemSpec spec) {
        return readHome(spec::matches, StoreContents.Reading::matching, true);
    }

    @Override
    public OptionalInt foodAtHome() {
        return readHome(Food.SPEC::matches, StoreContents.Reading::nutrition, false);
    }

    @Override
    public OptionalInt freeSlotsAtHome() {
        return readHome(id -> false, StoreContents.Reading::free, false);
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
            if (row.kind().equals(kind) && party.equals(row.party()) && atHome(area, row.at())) {
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

    /**
     * One number summed over HOME's stores, or empty when any of them could not be read; with
     * {@code reachable}, only those with a side a body could stand at, by the take's own test.
     */
    private OptionalInt readHome(Predicate<String> ids, ToIntFunction<StoreContents.Reading> part,
                                 boolean reachable) {
        Home home = progress.home();
        if (home == null) {
            return OptionalInt.empty();
        }
        SortedSet<ChunkKey> area = area();
        Set<BlockPos> counted = new HashSet<>();
        LevelProbe probe = reachable ? new LevelProbe(server.overworld()) : null;
        int total = 0;
        for (PlaceRow row : PlacesData.get(server).places().rows()) {
            if (!row.kind().equals(Store.POI) || !party.equals(row.party()) || !atHome(area, row.at())) {
                continue;
            }
            if (probe != null && !EnsureTable.WalkToKnown.hasOpenSide(row.at(), probe)) {
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

    private static boolean atHome(Set<ChunkKey> area, Pos at) {
        return area.contains(ChunkKey.at(ChunkKey.OVERWORLD, at.x(), at.z()));
    }
}
