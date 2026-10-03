package dev.luizloyola.autarkia.mod.builder;

import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.social.PartyId;
import dev.luizloyola.anima.core.social.PlaceRow;
import dev.luizloyola.anima.core.social.Places;
import dev.luizloyola.anima.core.territory.ChunkKey;
import dev.luizloyola.anima.mod.social.PartyData;
import dev.luizloyola.anima.mod.social.PlacesData;
import dev.luizloyola.anima.mod.territory.Territories;
import dev.luizloyola.autarkia.compat.inv.StoreContents;
import dev.luizloyola.autarkia.core.board.Deconstruct;
import dev.luizloyola.autarkia.core.board.PartyBoard;
import dev.luizloyola.autarkia.core.board.SetUp;
import dev.luizloyola.autarkia.core.bp.Footprint;
import dev.luizloyola.autarkia.core.builder.Structure;
import dev.luizloyola.autarkia.core.builder.Structure.Phase;
import dev.luizloyola.autarkia.core.direction.Direction;
import dev.luizloyola.autarkia.core.direction.StorageLine;
import dev.luizloyola.autarkia.mod.board.PartyBoards;
import dev.luizloyola.autarkia.mod.direction.Directions;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * A house built: its stations become the party's, and the first house replaces the starter base
 * (docs/superpowers/specs/2026-10-01-house-site-design.md, decision 6; *After the choice*, 5). The
 * starter chest and workbench are released and taken down by a {@link Deconstruct}, the chest
 * emptied into the house's first. The furnace stays: the basic house has none.
 *
 * <p>A building grown into a station it lacked takes the party's of that kind outside every building
 * down the same way (docs/superpowers/specs/2026-10-02-house-grows-design.md): the starter furnace,
 * once {@code base=lv3}'s stands inside.
 *
 * <p>A store left outside every building is folded in once the buildings have room for what it
 * holds ({@link #foldStrays}).
 */
final class MovingIn {

    /** How far above layer 0 a building's stations are looked for. */
    private static final int HEIGHT = 16;

    static final List<SetUp.Station> STATIONS = List.of(SetUp.WORKBENCH, SetUp.STORE, SetUp.FURNACE);

    /** As a flatten's and a build's (ruling 23). */
    private static final double PRIORITY = 0.5;

    private MovingIn() {
    }

    /**
     * @param grown whether it stood already and has grown, rather than been built
     * @return what was done, for the members' journals; empty when nothing was
     */
    static List<String> moveIn(MinecraftServer server, PartyId party, Structure built, boolean grown) {
        List<String> said = new ArrayList<>();
        ServerLevel level = server.overworld();
        Places places = PlacesData.get(server).places();
        long now = level.getGameTime();
        Set<PoiKind> housed = new LinkedHashSet<>();
        Footprint walls = built.built();
        Set<PoiKind> held = new LinkedHashSet<>();
        for (PlaceRow row : places.rows()) {
            if (party.equals(row.party()) && inside(walls, row.at())) {
                held.add(row.kind());
            }
        }
        int claimed = 0;
        for (int x = walls.minX(); x <= walls.maxX(); x++) {
            for (int z = walls.minZ(); z <= walls.maxZ(); z++) {
                for (int y = built.anchor().y(); y <= built.anchor().y() + HEIGHT; y++) {
                    String id = BuiltInRegistries.BLOCK.getKey(level.getBlockState(new BlockPos(x, y, z)).getBlock())
                            .toString();
                    for (SetUp.Station station : STATIONS) {
                        if (station.itemId().equals(id)) {
                            places.found(station.kind(), new Pos(x, y, z), null, party, now);
                            housed.add(station.kind());
                            claimed++;
                        }
                    }
                }
            }
        }
        if (claimed > 0) {
            said.add("claimed " + claimed + " stations in " + built.blueprint());
        }
        List<Structure> structures = StructuresData.get(server).of(party);
        if (grown) {
            Set<PoiKind> added = new LinkedHashSet<>(housed);
            added.removeAll(held);
            replace(server, party, built, structures, added, said);
            return said;
        }
        boolean first = structures.stream().noneMatch(other -> !other.id().equals(built.id()) && other.stands());
        if (!first || !housed.contains(SetUp.STORE.kind())) {
            return said;
        }
        Set<PoiKind> starter = new LinkedHashSet<>(housed);
        starter.retainAll(List.of(SetUp.STORE.kind(), SetUp.WORKBENCH.kind()));
        replace(server, party, built, structures, starter, said);
        return said;
    }

    /**
     * Releases the party's stations of these kinds in its area and outside every building, and
     * posts their taking down; a chest's goods are carried into the building first.
     */
    private static void replace(MinecraftServer server, PartyId party, Structure built, List<Structure> structures,
                                Set<PoiKind> kinds, List<String> said) {
        if (kinds.isEmpty()) {
            return;
        }
        Places places = PlacesData.get(server).places();
        SortedSet<ChunkKey> area = Territories.of(server).area(party);
        List<Deconstruct.Target> targets = new ArrayList<>();
        for (PlaceRow row : List.copyOf(places.rows())) {
            SetUp.Station station = STATIONS.stream().filter(s -> s.kind().equals(row.kind())).findFirst().orElse(null);
            if (station == null || !kinds.contains(row.kind()) || !party.equals(row.party())
                    || !area.contains(ChunkKey.at(ChunkKey.OVERWORLD, row.at().x(), row.at().z()))
                    || structures.stream().anyMatch(s -> inside(s.built(), row.at()))) {
                continue;
            }
            // Released first, or the emptying would carry the goods straight back into it.
            places.drop(row.kind(), row.at());
            targets.add(new Deconstruct.Target(row.at(), station.itemId(), station == SetUp.STORE));
        }
        if (!targets.isEmpty()) {
            PartyBoards.of(server, party).post(new Deconstruct(targets, "moved into " + built.blueprint(), PRIORITY));
            PartyBoards.touch(server);
            said.add("taking down what " + built.blueprint() + " replaces: " + targets.size()
                    + (targets.size() == 1 ? " block" : " blocks"));
        }
    }

    /**
     * Takes down the party's stores in its area but outside every building, their goods carried in,
     * once the stores inside have room for them. Moving in had taken the starter chests down into a
     * full house, so a new chest went up outside; the house grew five empty chests six seconds later,
     * and the one outside stood full for good (forest, 2026-10-03). Not while a building goes up or
     * grows, since a store set up then goes outside on purpose, nor while another taking down is on
     * the board. The free slots the storage line keeps are left free: a store set up outside because
     * a full house could not grow is still wanted, and folding it would only have it set up again.
     *
     * @return what was done, for the members' journals; empty when nothing was
     */
    static List<String> foldStrays(MinecraftServer server, PartyId party, List<Structure> structures) {
        List<Structure> standing = structures.stream().filter(Structure::stands).toList();
        if (standing.isEmpty() || structures.stream().anyMatch(s -> s.phase() != Phase.BUILT
                && s.phase() != Phase.REFUSED && s.phase() != Phase.SITED)) {
            return List.of();
        }
        Places places = PlacesData.get(server).places();
        SortedSet<ChunkKey> area = Territories.of(server).area(party);
        List<PlaceRow> housed = new ArrayList<>();
        List<PlaceRow> strays = new ArrayList<>();
        for (PlaceRow row : places.rows()) {
            if (!party.equals(row.party()) || !row.kind().equals(SetUp.STORE.kind())) {
                continue;
            }
            if (standing.stream().anyMatch(s -> inside(s.built(), row.at()))) {
                housed.add(row);
            } else if (area.contains(ChunkKey.at(ChunkKey.OVERWORLD, row.at().x(), row.at().z()))) {
                strays.add(row);
            }
        }
        PartyBoard board = PartyBoards.of(server, party);
        if (strays.isEmpty() || housed.isEmpty()
                || board.projects().stream().anyMatch(project -> project instanceof Deconstruct)) {
            return List.of();
        }
        ServerLevel level = server.overworld();
        Set<BlockPos> counted = new HashSet<>();
        int free = 0;
        for (PlaceRow row : housed) {
            Optional<StoreContents.Reading> read = StoreContents.read(level, row.at(), id -> true, counted);
            if (read.isEmpty()) {
                return List.of(); // unloaded: no room is known
            }
            free += read.get().free();
        }
        List<Integer> used = new ArrayList<>();
        for (PlaceRow row : strays) {
            Optional<StoreContents.Reading> read = StoreContents.read(level, row.at(), id -> true, counted);
            if (read.isEmpty()) {
                return List.of();
            }
            used.add(read.get().used());
        }
        List<Deconstruct.Target> targets = new ArrayList<>();
        for (int index : folding(used, free - keptFree(server, party))) {
            PlaceRow row = strays.get(index);
            // Released first, or the emptying would carry the goods straight back into it.
            places.drop(row.kind(), row.at());
            targets.add(new Deconstruct.Target(row.at(), SetUp.STORE.itemId(), true));
        }
        if (targets.isEmpty()) {
            return List.of();
        }
        String into = standing.get(0).blueprint();
        board.post(new Deconstruct(targets, "moved into " + into, PRIORITY));
        PartyBoards.touch(server);
        return List.of("folding " + targets.size() + (targets.size() == 1 ? " store" : " stores")
                + " left outside into " + into);
    }

    /** Which of the stores, by index, fold in: each whose used slots still fit in the room left, in order. */
    static List<Integer> folding(List<Integer> used, int room) {
        List<Integer> fold = new ArrayList<>();
        for (int i = 0; i < used.size(); i++) {
            if (used.get(i) <= room) {
                fold.add(i);
                room -= used.get(i);
            }
        }
        return fold;
    }

    /** The free slots the storage line keeps the party, at the most any age asks. */
    private static int keptFree(MinecraftServer server, PartyId party) {
        int perMember = Directions.tree().nodes().stream().flatMap(node -> node.directions().stream())
                .filter(direction -> direction.id().line().equals(StorageLine.INSTANCE.id()))
                .mapToInt(Direction::count).max().orElse(0);
        return perMember * PartyData.get(server).members(party).size();
    }

    private static boolean inside(Footprint f, Pos at) {
        return at.x() >= f.minX() && at.x() <= f.maxX() && at.z() >= f.minZ() && at.z() <= f.maxZ();
    }
}
