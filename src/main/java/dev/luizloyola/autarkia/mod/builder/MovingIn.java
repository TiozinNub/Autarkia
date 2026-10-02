package dev.luizloyola.autarkia.mod.builder;

import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.social.PartyId;
import dev.luizloyola.anima.core.social.PlaceRow;
import dev.luizloyola.anima.core.social.Places;
import dev.luizloyola.anima.core.territory.ChunkKey;
import dev.luizloyola.anima.mod.social.PlacesData;
import dev.luizloyola.anima.mod.territory.Territories;
import dev.luizloyola.autarkia.core.board.Deconstruct;
import dev.luizloyola.autarkia.core.board.SetUp;
import dev.luizloyola.autarkia.core.bp.Footprint;
import dev.luizloyola.autarkia.core.builder.Structure;
import dev.luizloyola.autarkia.mod.board.PartyBoards;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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

    private static boolean inside(Footprint f, Pos at) {
        return at.x() >= f.minX() && at.x() <= f.maxX() && at.z() >= f.minZ() && at.z() <= f.maxZ();
    }
}
