package dev.luizloyola.autarkia.mod.builder;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.knowledge.AgentKnowledge;
import dev.luizloyola.anima.core.brain.knowledge.KnowledgeRegistry;
import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.brain.knowledge.PoiMemory;
import dev.luizloyola.anima.core.brain.knowledge.ShelterNoter;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.social.PartyId;
import dev.luizloyola.anima.mod.brain.KnowledgeData;
import dev.luizloyola.anima.mod.brain.Shelters;
import dev.luizloyola.anima.mod.social.PartyData;
import dev.luizloyola.autarkia.core.bp.Footprint;
import dev.luizloyola.autarkia.core.builder.Structure;
import dev.luizloyola.autarkia.mod.entity.Person;
import dev.luizloyola.autarkia.mod.entity.Persons;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import org.jspecify.annotations.Nullable;

/**
 * Every member is shown the party's standing buildings (Luiz, 2026-10-20: everyone learns the hut):
 * one who remembers no way into a building has the space inside its door checked on its behalf,
 * as though it had stopped there ({@link Shelters#inspect}). Worked out again each beat from the
 * buildings and the members' memories, so a restart loses nothing.
 */
final class ShelterShowing {

    /**
     * How long before a member is shown the same building again: a check that finds no shelter
     * there (a door left open, a wall down) would otherwise be asked every beat. Lost on a restart,
     * which only asks sooner.
     */
    static final int AGAIN_TICKS = 1_200;

    private static final Map<String, Long> SHOWN = new HashMap<>();

    private ShelterShowing() {
    }

    static void show(MinecraftServer server, PartyId party, List<Structure> structures) {
        ServerLevel level = server.overworld();
        long now = level.getGameTime();
        KnowledgeRegistry known = KnowledgeData.get(server).registry();
        List<AgentId> members = PartyData.get(server).members(party);
        for (Structure structure : structures) {
            if (!structure.stands()) {
                continue;
            }
            @Nullable BlockPos inside = null;
            for (AgentId member : members) {
                String key = member + "/" + structure.id();
                if (knows(known.forPerson(member), structure) || !due(SHOWN, key, now)) {
                    continue;
                }
                Person body = Persons.findLoaded(server, member);
                if (body == null) {
                    continue;
                }
                if (inside == null) {
                    inside = inside(level, structure);
                    if (inside == null) {
                        break;
                    }
                }
                SHOWN.put(key, now);
                Shelters.inspect(body, inside);
            }
        }
    }

    /** Whether this body remembers a way into the building: a shelter it keeps inside its walls. */
    static boolean knows(AgentKnowledge knowledge, Structure structure) {
        Footprint walls = structure.built();
        int floor = structure.anchor().y();
        for (PoiMemory way : knowledge.sighted(PoiKind.SHELTER)) {
            Pos in = way.anchor();
            if (!ShelterNoter.dug(way) && in.x() >= walls.minX() && in.x() <= walls.maxX()
                    && in.z() >= walls.minZ() && in.z() <= walls.maxZ()
                    && in.y() >= floor && in.y() <= floor + Structure.WALLS_HIGH) {
                return true;
            }
        }
        return false;
    }

    /** Whether {@code key} was last shown {@link #AGAIN_TICKS} ago or more, or never. */
    static boolean due(Map<String, Long> shown, String key, long now) {
        Long last = shown.get(key);
        return last == null || now - last >= AGAIN_TICKS;
    }

    /**
     * The cell a body stands in just inside the building's first door, read off the world: the
     * open side of the door's lower half nearer the middle of its walls. Null with no door standing,
     * or the walls not loaded.
     */
    private static @Nullable BlockPos inside(ServerLevel level, Structure structure) {
        Footprint walls = structure.built();
        double midX = (walls.minX() + walls.maxX()) / 2.0;
        double midZ = (walls.minZ() + walls.maxZ()) / 2.0;
        int floor = structure.anchor().y();
        for (int x = walls.minX(); x <= walls.maxX(); x++) {
            for (int z = walls.minZ(); z <= walls.maxZ(); z++) {
                for (int y = floor; y <= floor + Structure.WALLS_HIGH; y++) {
                    BlockPos at = new BlockPos(x, y, z);
                    if (!level.isLoaded(at)) {
                        return null;
                    }
                    BlockState state = level.getBlockState(at);
                    if (!state.is(BlockTags.DOORS) || !state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                            || state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) != DoubleBlockHalf.LOWER) {
                        continue;
                    }
                    @Nullable BlockPos best = null;
                    double nearest = Double.POSITIVE_INFINITY;
                    for (Direction side : Direction.Plane.HORIZONTAL) {
                        BlockPos cell = at.relative(side);
                        double distance = Math.hypot(cell.getX() - midX, cell.getZ() - midZ);
                        if (distance < nearest && level.getBlockState(cell).getCollisionShape(level, cell).isEmpty()) {
                            best = cell;
                            nearest = distance;
                        }
                    }
                    if (best != null) {
                        return best;
                    }
                }
            }
        }
        return null;
    }
}
