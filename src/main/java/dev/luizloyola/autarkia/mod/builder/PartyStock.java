package dev.luizloyola.autarkia.mod.builder;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.knowledge.PoiMemory;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.inv.Inventory;
import dev.luizloyola.anima.core.social.PartyId;
import dev.luizloyola.anima.core.social.PlaceRow;
import dev.luizloyola.anima.core.store.Store;
import dev.luizloyola.anima.core.territory.ChunkKey;
import dev.luizloyola.anima.mod.social.PartyData;
import dev.luizloyola.anima.mod.social.PlacesData;
import dev.luizloyola.anima.mod.territory.Territories;
import dev.luizloyola.autarkia.compat.inv.StoreContents;
import dev.luizloyola.autarkia.core.bp.Stock;
import dev.luizloyola.autarkia.core.tree.Pois;
import dev.luizloyola.autarkia.mod.bp.Blueprints;
import dev.luizloyola.autarkia.mod.entity.Person;
import dev.luizloyola.autarkia.mod.entity.Persons;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;

/**
 * What a party has to build with, for {@link Stock}: its stores at HOME and its loaded members'
 * packs, and the trees those members remember near its area. Server thread only.
 */
public final class PartyStock {

    /** How far past the area a remembered tree still counts as near. */
    private static final int NEAR = 64;

    private PartyStock() {
    }

    public static Stock of(MinecraftServer server, PartyId party) {
        SortedSet<ChunkKey> area = new TreeSet<>();
        for (ChunkKey chunk : Territories.of(server).area(party)) {
            if (chunk.dimension().equals(ChunkKey.OVERWORLD)) {
                area.add(chunk);
            }
        }
        Map<String, Integer> held = new HashMap<>();
        Set<BlockPos> counted = new HashSet<>();
        for (PlaceRow row : PlacesData.get(server).places().rows()) {
            if (row.kind().equals(Store.POI) && party.equals(row.party())
                    && area.contains(ChunkKey.at(ChunkKey.OVERWORLD, row.at().x(), row.at().z()))) {
                StoreContents.tally(server.overworld(), row.at(), counted, held);
            }
        }
        // One grove remembered by two members is one grove.
        Map<Pos, PoiMemory> trees = new HashMap<>();
        for (AgentId member : PartyData.get(server).members(party)) {
            Person person = Persons.findLoaded(server, member);
            if (person == null) {
                continue;
            }
            for (Inventory.Entry entry : person.inventory().occupied()) {
                held.merge(entry.stack().id(), entry.stack().count(), Integer::sum);
            }
            for (PoiMemory tree : person.brain().knowledge().all(Pois.TREE)) {
                if (near(area, tree.anchor())) {
                    trees.merge(tree.anchor(), tree, (a, b) -> a.units() >= b.units() ? a : b);
                }
            }
        }
        Map<String, Integer> growing = new HashMap<>();
        for (PoiMemory tree : trees.values()) {
            if (!tree.detail().isEmpty()) {
                growing.merge(tree.detail(), tree.units(), Integer::sum);
            }
        }
        return Stock.of(Blueprints.dictionary(), held, growing);
    }

    private static boolean near(SortedSet<ChunkKey> area, Pos at) {
        for (ChunkKey chunk : area) {
            if (at.x() >= chunk.minBlockX() - NEAR && at.x() <= chunk.maxBlockX() + NEAR
                    && at.z() >= chunk.minBlockZ() - NEAR && at.z() <= chunk.maxBlockZ() + NEAR) {
                return true;
            }
        }
        return false;
    }
}
