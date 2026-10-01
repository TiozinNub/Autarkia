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
 */
final class MovingIn {

    /** How far above layer 0 a building's stations are looked for. */
    private static final int HEIGHT = 16;

    private static final List<SetUp.Station> STATIONS = List.of(SetUp.WORKBENCH, SetUp.STORE, SetUp.FURNACE);

    /** As a flatten's and a build's (ruling 23). */
    private static final double PRIORITY = 0.5;

    private MovingIn() {
    }

    /** @return what was done, for the members' journals; empty when nothing was */
    static List<String> moveIn(MinecraftServer server, PartyId party, Structure built) {
        List<String> said = new ArrayList<>();
        ServerLevel level = server.overworld();
        Places places = PlacesData.get(server).places();
        long now = level.getGameTime();
        Set<PoiKind> housed = new LinkedHashSet<>();
        Footprint walls = built.built();
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
        boolean first = structures.stream().noneMatch(other -> !other.id().equals(built.id())
                && other.phase() == Structure.Phase.BUILT);
        if (!first || !housed.contains(SetUp.STORE.kind())) {
            return said;
        }
        SortedSet<ChunkKey> area = Territories.of(server).area(party);
        List<Deconstruct.Target> targets = new ArrayList<>();
        for (PlaceRow row : List.copyOf(places.rows())) {
            boolean starter = (row.kind().equals(SetUp.STORE.kind()) || row.kind().equals(SetUp.WORKBENCH.kind()))
                    && housed.contains(row.kind());
            if (!starter || !party.equals(row.party())
                    || !area.contains(ChunkKey.at(ChunkKey.OVERWORLD, row.at().x(), row.at().z()))
                    || structures.stream().anyMatch(s -> inside(s.built(), row.at()))) {
                continue;
            }
            // Released first, or the emptying would carry the goods straight back into it.
            places.drop(row.kind(), row.at());
            boolean chest = row.kind().equals(SetUp.STORE.kind());
            targets.add(new Deconstruct.Target(row.at(), chest ? SetUp.STORE.itemId() : SetUp.WORKBENCH.itemId(),
                    chest));
        }
        if (!targets.isEmpty()) {
            PartyBoards.of(server, party).post(new Deconstruct(targets, "moved into " + built.blueprint(), PRIORITY));
            PartyBoards.touch(server);
            said.add("taking down the starter base: " + targets.size() + (targets.size() == 1 ? " block" : " blocks"));
        }
        return said;
    }

    private static boolean inside(Footprint f, Pos at) {
        return at.x() >= f.minX() && at.x() <= f.maxX() && at.z() >= f.minZ() && at.z() <= f.maxZ();
    }
}
