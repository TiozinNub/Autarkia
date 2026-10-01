package dev.luizloyola.autarkia.mod.builder;

import dev.luizloyola.anima.compat.terrain.NaturalGroundReader;
import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.log.Category;
import dev.luizloyola.anima.core.social.PartyId;
import dev.luizloyola.anima.core.terrain.NaturalGround;
import dev.luizloyola.anima.core.territory.ChunkKey;
import dev.luizloyola.anima.mod.log.Journals;
import dev.luizloyola.anima.mod.social.PartyData;
import dev.luizloyola.anima.mod.territory.Territories;
import dev.luizloyola.autarkia.core.board.Flatten;
import dev.luizloyola.autarkia.core.board.PartyBoard;
import dev.luizloyola.autarkia.core.board.Project;
import dev.luizloyola.autarkia.core.bp.Footprint;
import dev.luizloyola.autarkia.core.builder.Structure;
import dev.luizloyola.autarkia.core.builder.Structure.Phase;
import dev.luizloyola.autarkia.core.direction.Home;
import dev.luizloyola.autarkia.core.direction.PartyProgress;
import dev.luizloyola.autarkia.core.earthwork.FlattenPlan;
import dev.luizloyola.autarkia.mod.board.PartyBoards;
import dev.luizloyola.autarkia.mod.direction.DirectionsData;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.Collectors;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * Moves a party's sited buildings along (docs/superpowers/specs/2026-10-01-house-site-design.md,
 * *After the choice*): a site waits for its ground to be cleared, then its pad is flattened, dead
 * level at the height it was chosen at. The building itself waits for the builder.
 */
public final class Structures {

    /** How often sited buildings are looked at: their ground clears in minutes, not ticks. */
    private static final int BEAT_TICKS = 100;
    /** As a felling's, so a flatten neither jumps the queue nor waits behind every gather. */
    private static final double FLATTEN_PRIORITY = 0.5;

    private Structures() {
    }

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % BEAT_TICKS == 0) {
                beat(server);
            }
        });
        PartyBoards.onClosed(Structures::closed);
    }

    private static void beat(MinecraftServer server) {
        StructuresData data = StructuresData.get(server);
        for (Map.Entry<PartyId, List<Structure>> entry : data.parties().entrySet()) {
            PartyId party = entry.getKey();
            for (Structure structure : List.copyOf(entry.getValue())) {
                Structure next = switch (structure.phase()) {
                    case SITED -> level(server, party, structure);
                    case LEVELLING -> stillLevelling(server, party, structure);
                    case LEVELLED, REFUSED -> structure;
                };
                if (next != structure) {
                    data.replace(party, next);
                    tell(server, party, next);
                }
            }
        }
    }

    /** Its ground cleared, the pad's flatten goes on the board; until then it waits. */
    private static Structure level(MinecraftServer server, PartyId party, Structure structure) {
        Home home = DirectionsData.get(server).find(party).map(PartyProgress::home).orElse(null);
        if (home == null || !cleared(server, party, home, structure)) {
            return structure;
        }
        Footprint pad = structure.pad();
        ServerLevel level = server.overworld();
        NaturalGround scan = NaturalGroundReader.read(level, pad.minX() - Structure.RING,
                pad.minZ() - Structure.RING, pad.maxX() + Structure.RING, pad.maxZ() + Structure.RING);
        FlattenPlan plan = FlattenPlan.of(scan, pad.minX(), pad.minZ(), pad.maxX(), pad.maxZ(),
                new FlattenPlan.Rules(0, OptionalInt.of(structure.anchor().y()), FlattenPlan.Rules.SMOOTHING,
                        FlattenPlan.Rules.MAX_RING));
        if (plan.refused()) {
            String why = plan.refusals().stream().map(r -> r.why().name().toLowerCase(java.util.Locale.ROOT))
                    .collect(Collectors.groupingBy(w -> w, java.util.TreeMap::new, Collectors.counting()))
                    .toString();
            return structure.at(Phase.REFUSED, "the pad cannot be levelled: " + why);
        }
        PartyBoards.of(server, party).post(Flatten.of(plan, pad.minX(), pad.minZ(), pad.maxX(), pad.maxZ(), 0,
                FLATTEN_PRIORITY));
        PartyBoards.touch(server);
        return structure.at(Phase.LEVELLING, "");
    }

    /**
     * Whether the pad and the ring a flatten eases into are cleared — those of their chunks the
     * area holds. A margin chunk skipped as taken is nobody's to clear, and waiting on it would be
     * waiting for ever; the flatten refuses a tree there itself.
     */
    private static boolean cleared(MinecraftServer server, PartyId party, Home home, Structure structure) {
        SortedSet<ChunkKey> area = new TreeSet<>(Territories.of(server).area(party));
        for (ChunkKey chunk : structure.groundChunks()) {
            if (area.contains(chunk) && !home.cleared().contains(chunk)) {
                return false;
            }
        }
        return true;
    }

    /** A flatten gone from the board without finishing was cancelled: the site waits to be levelled again. */
    private static Structure stillLevelling(MinecraftServer server, PartyId party, Structure structure) {
        for (Project project : PartyBoards.of(server, party).projects()) {
            if (project instanceof Flatten flatten && levels(flatten, structure)) {
                return structure;
            }
        }
        return structure.at(Phase.SITED, "");
    }

    private static void closed(MinecraftServer server, PartyId party, List<Project> finished) {
        StructuresData data = StructuresData.get(server);
        for (Structure structure : data.of(party)) {
            if (structure.phase() != Phase.LEVELLING) {
                continue;
            }
            for (Project project : finished) {
                if (project instanceof Flatten flatten && levels(flatten, structure)) {
                    Structure levelled = structure.at(Phase.LEVELLED, "");
                    data.replace(party, levelled);
                    tell(server, party, levelled);
                }
            }
        }
    }

    /** Whether this flatten is the one over this building's pad: its columns, at any height. */
    static boolean levels(Flatten flatten, Structure structure) {
        Footprint pad = structure.pad();
        return flatten.area().min().x() == pad.minX() && flatten.area().min().z() == pad.minZ()
                && flatten.area().max().x() == pad.maxX() && flatten.area().max().z() == pad.maxZ();
    }

    private static void tell(MinecraftServer server, PartyId party, Structure structure) {
        String line = describe(structure);
        for (AgentId member : PartyData.get(server).members(party)) {
            Journals.of(server).record(member, Category.PROJECT, "structures", line);
        }
    }

    /** One line for the journals and the command: what, where, and how far along. */
    public static String describe(Structure structure) {
        String at = "(" + structure.anchor().x() + ", " + structure.anchor().y() + ", " + structure.anchor().z() + ")";
        String phase = structure.phase().name().toLowerCase(java.util.Locale.ROOT);
        return structure.blueprint() + " at " + at + " facing "
                + structure.placement().north().word() + (structure.placement().flip() ? " flipped" : "")
                + " — " + phase + (structure.note().isEmpty() ? "" : ": " + structure.note());
    }
}
