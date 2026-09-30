package dev.luizloyola.autarkia.mod.direction;

import dev.luizloyola.anima.compat.terrain.GroundReader;
import dev.luizloyola.anima.core.brain.knowledge.AgentKnowledge;
import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.brain.knowledge.PoiMemory;
import dev.luizloyola.anima.core.brain.knowledge.Sighting;
import dev.luizloyola.anima.core.terrain.GroundSample;
import dev.luizloyola.anima.core.terrain.Landscape;
import dev.luizloyola.anima.core.terrain.Terrain;
import dev.luizloyola.anima.core.terrain.TerrainRules;
import dev.luizloyola.anima.core.social.PartyId;
import dev.luizloyola.autarkia.compat.home.HomeRecords;
import dev.luizloyola.autarkia.core.direction.Home;
import dev.luizloyola.autarkia.core.direction.HomeJudge;
import dev.luizloyola.autarkia.core.direction.HomeJudge.Avoid;
import dev.luizloyola.autarkia.core.direction.HomeJudge.Judgement;
import dev.luizloyola.autarkia.core.direction.HomeJudge.Keep;
import dev.luizloyola.autarkia.core.direction.HomeJudge.KeepColumns;
import dev.luizloyola.autarkia.core.direction.HomeJudge.Refusal;
import dev.luizloyola.autarkia.core.direction.HomeKnob;
import dev.luizloyola.autarkia.core.patch.Landmarks;
import dev.luizloyola.autarkia.core.direction.HomeJudge.Known;
import dev.luizloyola.autarkia.core.direction.HomeJudge.Table;
import dev.luizloyola.autarkia.core.direction.HomeJudge.Want;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * The judge pointed at a place in the world: the ground read around it, what one settler knows of
 * the wants its eyes supply, and what keeps a home away. The ground and the structures are read
 * directly; stone and bee nests are only what this settler has seen
 * (docs/superpowers/specs/2026-09-25-home-search-design.md).
 */
public final class HomeChooser {

    /** Where each want the eyes supply is remembered. */
    private static final Map<Want, PoiKind> KINDS =
            Map.of(Want.STONE, Landmarks.STONE_POI, Want.BEE, Landmarks.BEES);

    /**
     * Every allowed plot within the read radius of the centre, best first; the table used, the
     * ground read, and what the settler knew.
     */
    public record Choice(Table table, Judgement judgement, Landscape land, Map<Want, List<Known>> known) {
    }

    /** How far past a structure's start its record may reach: a village's streets. */
    private static final int STRUCTURE_REACH = 128;

    private HomeChooser() {
    }

    /**
     * The plots around {@code centre} as a member of {@code party} would judge them, knowing what
     * {@code knowledge} knows.
     */
    public static Choice choose(ServerLevel level, BlockPos centre, int radius, PartyId party,
                                AgentKnowledge knowledge) {
        Table table = Table.configured().withReadRadius(radius);
        TerrainRules rules = table.rules(TerrainRules.configured());
        int h = table.size() / 2;
        int reach = radius + Math.max(table.margin(), h + HomeKnob.AVOID_VILLAGE.i())
                + Terrain.reach(rules);
        GroundSample sample = GroundReader.read(level, centre.getX() - reach, centre.getZ() - reach,
                centre.getX() + reach, centre.getZ() + reach);
        Terrain terrain = Terrain.analyse(sample, rules);
        Avoid avoid = avoid(level, centre, radius + h, party, terrain);
        Landscape land = new Landscape(terrain);
        Map<Want, List<Known>> known = known(knowledge);
        return new Choice(table, HomeJudge.judge(land, centre.getX(), centre.getZ(), table, known, avoid),
                land, known);
    }

    /**
     * What keeps a home away from plots reaching {@code reach} from {@code centre}: other parties'
     * plots, the structures the server recorded, and refused biomes.
     */
    private static Avoid avoid(ServerLevel level, BlockPos centre, int reach, PartyId party,
                               Terrain terrain) {
        List<Keep> boxes = new ArrayList<>();
        int partyAway = HomeKnob.AVOID_PARTY.i();
        DirectionsData.get(level.getServer()).parties().forEach((other, progress) -> {
            Home home = progress.home();
            if (home != null && !other.equals(party)) {
                boxes.add(new Keep(Refusal.PARTY, home.plot().min().x(), home.plot().min().z(),
                        home.plot().max().x(), home.plot().max().z(), partyAway));
            }
        });
        int far = reach + Math.max(Math.max(HomeKnob.AVOID_MONSTERS.i(), HomeKnob.AVOID_VILLAGE.i()),
                Math.max(HomeKnob.AVOID_TEMPLE.i(), HomeKnob.AVOID_PORTAL.i())) + STRUCTURE_REACH;
        HomeRecords.Structures structures = HomeRecords.structures(level, centre.getX() - far,
                centre.getZ() - far, centre.getX() + far, centre.getZ() + far, why -> switch (why) {
                    case MONSTERS -> HomeKnob.AVOID_MONSTERS.i();
                    case TEMPLE -> HomeKnob.AVOID_TEMPLE.i();
                    default -> HomeKnob.AVOID_PORTAL.i();
                });
        boxes.addAll(structures.keeps());
        List<KeepColumns> columns = new ArrayList<>();
        if (!structures.villages().isEmpty()) {
            // Measured from its buildings — the used ground in its record — not the record's box,
            // which takes in every road and field.
            List<BoundingBox> villages = structures.villages();
            columns.add(new KeepColumns(Refusal.VILLAGE, (x, z) -> terrain.kind(x, z) == Terrain.Kind.USED
                    && villages.stream().anyMatch(box -> x >= box.minX() && x <= box.maxX()
                            && z >= box.minZ() && z <= box.maxZ()),
                    HomeKnob.AVOID_VILLAGE.i()));
        }
        int sea = level.getSeaLevel();
        columns.add(new KeepColumns(Refusal.BIOME, HomeRecords.refusedBiome(level, (x, z) -> {
            int ground = terrain.ground(x, z);
            return ground == GroundSample.UNKNOWN ? sea : ground;
        }), 0));
        return new Avoid(boxes, columns);
    }

    /** What the settler remembers or has glimpsed of each want its eyes supply. */
    static Map<Want, List<Known>> known(AgentKnowledge knowledge) {
        Map<Want, List<Known>> out = new EnumMap<>(Want.class);
        KINDS.forEach((want, kind) -> {
            List<Known> each = new ArrayList<>();
            for (PoiMemory memory : knowledge.all(kind)) {
                each.add(new Known(memory.anchor().x(), memory.anchor().z(), memory.units()));
            }
            for (Sighting sighting : knowledge.glimpses(kind)) {
                each.add(new Known(sighting.at().x(), sighting.at().z(), Known.GLIMPSED));
            }
            out.put(want, each);
        });
        return out;
    }
}
