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
import dev.luizloyola.autarkia.core.direction.HomeJudge;
import dev.luizloyola.autarkia.core.direction.HomeJudge.Candidate;
import dev.luizloyola.autarkia.core.direction.HomeJudge.Known;
import dev.luizloyola.autarkia.core.direction.HomeJudge.Table;
import dev.luizloyola.autarkia.core.direction.HomeJudge.Want;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * The judge pointed at a place in the world: the ground read around it, and what one settler knows
 * of the wants its eyes supply. The ground is read directly, as the terrain view reads it; stone and
 * bee nests are only what this settler has seen (docs/superpowers/specs/2026-09-25-home-search-design.md).
 */
public final class HomeChooser {

    /** Where each want the eyes supply is remembered, by the kind's key. */
    private static final Map<Want, String> KINDS = Map.of(Want.STONE, "stone", Want.BEE, "bee");

    /** Every allowed plot within the read radius of the centre, best first, and the table used. */
    public record Choice(Table table, List<Candidate> ranked) {
    }

    private HomeChooser() {
    }

    public static Choice choose(ServerLevel level, BlockPos centre, int radius,
                                AgentKnowledge knowledge) {
        Table table = Table.configured().withReadRadius(radius);
        TerrainRules rules = table.rules(TerrainRules.configured());
        int reach = radius + table.margin() + Terrain.reach(rules);
        GroundSample sample = GroundReader.read(level, centre.getX() - reach, centre.getZ() - reach,
                centre.getX() + reach, centre.getZ() + reach);
        Landscape land = new Landscape(Terrain.analyse(sample, rules));
        return new Choice(table, HomeJudge.judge(land, centre.getX(), centre.getZ(), table, known(knowledge)));
    }

    /** What the settler remembers or has glimpsed of each want its eyes supply. */
    static Map<Want, List<Known>> known(AgentKnowledge knowledge) {
        Map<Want, List<Known>> out = new EnumMap<>(Want.class);
        KINDS.forEach((want, key) -> {
            Optional<PoiKind> kind = PoiKind.byKey(key);
            if (kind.isEmpty()) {
                return;
            }
            List<Known> each = new ArrayList<>();
            for (PoiMemory memory : knowledge.all(kind.get())) {
                each.add(new Known(memory.anchor().x(), memory.anchor().z(), memory.units()));
            }
            for (Sighting sighting : knowledge.glimpses(kind.get())) {
                each.add(new Known(sighting.at().x(), sighting.at().z(), Known.GLIMPSED));
            }
            out.put(want, each);
        });
        return out;
    }
}
