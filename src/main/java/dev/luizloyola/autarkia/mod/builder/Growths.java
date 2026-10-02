package dev.luizloyola.autarkia.mod.builder;

import dev.luizloyola.anima.compat.sense.LevelProbe;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.social.PartyId;
import dev.luizloyola.anima.core.social.Places;
import dev.luizloyola.anima.mod.social.PlacesData;
import dev.luizloyola.autarkia.compat.bp.Placer;
import dev.luizloyola.autarkia.core.board.Build;
import dev.luizloyola.autarkia.core.board.Deconstruct;
import dev.luizloyola.autarkia.core.board.GrowBuilding;
import dev.luizloyola.autarkia.core.board.PartyBoard;
import dev.luizloyola.autarkia.core.board.Project;
import dev.luizloyola.autarkia.core.board.SetUp;
import dev.luizloyola.autarkia.core.bp.Blueprint;
import dev.luizloyola.autarkia.core.bp.Blueprint.Outcome;
import dev.luizloyola.autarkia.core.bp.BuildPlan;
import dev.luizloyola.autarkia.core.bp.Chooser;
import dev.luizloyola.autarkia.core.bp.Diagnostics;
import dev.luizloyola.autarkia.core.bp.Footprint;
import dev.luizloyola.autarkia.core.bp.Planner;
import dev.luizloyola.autarkia.core.builder.Growth;
import dev.luizloyola.autarkia.core.builder.Structure;
import dev.luizloyola.autarkia.mod.board.PartyBoards;
import dev.luizloyola.autarkia.mod.bp.Blueprints;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * A standing building grows into a new selection of its variants for a station it lacks
 * (docs/superpowers/specs/2026-10-02-house-grows-design.md): whatever stands in a cell the growth
 * changes is taken down first, then the diff is built. Server thread only.
 */
public final class Growths {

    /** Ahead of the build it clears the way for, as the base's move is. */
    private static final double CLEARING_PRIORITY = 0.55;

    /** What each selection of a blueprint holds: planned once, since only the variants change it. */
    private static final Map<Blueprint, Map<Map<String, String>, Optional<Growth.Holds>>> HOLDS = new WeakHashMap<>();

    private Growths() {
    }

    /** The least growth of one of the party's standing buildings that adds a {@code station}. */
    public static Optional<Growth> of(List<Structure> structures, SetUp.Station station) {
        for (Structure structure : structures) {
            if (structure.phase() != Structure.Phase.BUILT) {
                continue;
            }
            Blueprint bp = blueprint(structure);
            if (bp == null) {
                continue;
            }
            Optional<Map<String, String>> next = Growth.choose(bp.variants(), structure.variants(), station.itemId(),
                    variants -> holds(bp, structure, variants));
            if (next.isPresent()) {
                return Optional.of(new Growth(structure.id(), next.get()));
            }
        }
        return Optional.empty();
    }

    /**
     * Takes an ask: the building records its growth, and what stands in its changed cells goes on a
     * {@link Deconstruct}. An ask for a building that no longer stands as it did is closed unheard.
     *
     * @return the building as it now is, or null when nothing changed
     */
    static @Nullable Structure start(MinecraftServer server, PartyId party, GrowBuilding ask, List<String> said) {
        ask.taken();
        PartyBoards.touch(server);
        Structure structure = StructuresData.get(server).of(party).stream()
                .filter(s -> s.id().equals(ask.structure())).findFirst().orElse(null);
        if (structure == null || structure.phase() != Structure.Phase.BUILT) {
            return null;
        }
        Blueprint bp = blueprint(structure);
        BuildPlan from = bp == null ? null : plan(bp, structure, structure.variants());
        BuildPlan to = bp == null ? null : plan(bp, structure, ask.variants());
        if (from == null || to == null) {
            said.add("cannot grow " + structure.blueprint() + " into " + ask.variants());
            return null;
        }
        Map<Pos, Outcome> changed = Growth.changed(from, to, structure.placement(), structure.anchor());
        List<Deconstruct.Target> inTheWay = inTheWay(server, party, changed);
        if (!inTheWay.isEmpty()) {
            PartyBoards.of(server, party).post(new Deconstruct(inTheWay, "making room to grow "
                    + structure.blueprint(), CLEARING_PRIORITY));
            said.add("taking down " + inTheWay.size() + (inTheWay.size() == 1 ? " block" : " blocks")
                    + " in the way of the growth");
        }
        return structure.growing(ask.variants(), "for a " + ask.station());
    }

    /**
     * A growing building's next step: wait while what was in the way comes down, then post the
     * build of the diff, and post it again if it went from the board unfinished.
     */
    static Structure step(MinecraftServer server, PartyId party, Structure structure) {
        PartyBoard board = PartyBoards.of(server, party);
        for (Project project : board.projects()) {
            // Finished but not yet closed counts too: closing it is what marks the growth built.
            if (project instanceof Build build && build.structure().equals(structure.id())) {
                return structure;
            }
            if (project instanceof Deconstruct deconstruct && !deconstruct.finished()
                    && deconstruct.targets().stream().anyMatch(t -> inside(structure.built(), t.at()))) {
                return structure;
            }
        }
        Blueprint bp = blueprint(structure);
        BuildPlan from = bp == null ? null : plan(bp, structure, structure.grownFrom());
        BuildPlan to = bp == null ? null : plan(bp, structure, structure.variants());
        List<String> notes = new ArrayList<>();
        Build build = from == null || to == null ? null : Builds.of(server.overworld(), structure, notes,
                Growth.changed(from, to, structure.placement(), structure.anchor()).keySet());
        if (build == null) {
            return structure.notGrown("cannot grow into " + structure.variants() + ": " + String.join("; ", notes));
        }
        board.post(build);
        PartyBoards.touch(server);
        return structure;
    }

    /**
     * The blocks standing in cells the growth changes that are not what it wants there: a chest put
     * down by the wall, a torch. Air, and what a placement replaces — grass, snow — are not in the
     * way. The party's claim on each is dropped first, or emptying a chest would refill it.
     */
    private static List<Deconstruct.Target> inTheWay(MinecraftServer server, PartyId party, Map<Pos, Outcome> changed) {
        ServerLevel level = server.overworld();
        Places places = PlacesData.get(server).places();
        List<Deconstruct.Target> targets = new ArrayList<>();
        for (Map.Entry<Pos, Outcome> cell : changed.entrySet()) {
            Pos at = cell.getKey();
            BlockPos pos = new BlockPos(at.x(), at.y(), at.z());
            BlockState state = level.getBlockState(pos);
            String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            boolean wanted = cell.getValue() != null && cell.getValue().block().equals(id);
            if (state.isAir() || wanted || cell.getValue() != null && state.canBeReplaced()) {
                continue;
            }
            for (var row : List.copyOf(places.rows())) {
                if (party.equals(row.party()) && row.at().equals(at)) {
                    places.drop(row.kind(), row.at());
                }
            }
            boolean container = level.getBlockEntity(pos) instanceof net.minecraft.world.Container
                    && !(level.getBlockEntity(pos)
                    instanceof net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity);
            targets.add(new Deconstruct.Target(at, id, container));
        }
        return targets;
    }

    private static Optional<Growth.Holds> holds(Blueprint bp, Structure structure, Map<String, String> variants) {
        return HOLDS.computeIfAbsent(bp, key -> new HashMap<>()).computeIfAbsent(variants, key -> {
            BuildPlan plan = plan(bp, structure, key);
            if (plan == null) {
                return Optional.empty();
            }
            Map<String, Integer> stations = new HashMap<>();
            int[] blocks = {0};
            plan.forEach(structure.placement(), (dx, layer, dz, kind, state) -> {
                if (kind == BuildPlan.CellKind.BLOCK && state != null) {
                    blocks[0]++;
                    for (SetUp.Station station : MovingIn.STATIONS) {
                        if (station.itemId().equals(state.block())) {
                            stations.merge(station.itemId(), 1, Integer::sum);
                        }
                    }
                }
            });
            return Optional.of(new Growth.Holds(stations, blocks[0]));
        });
    }

    /**
     * The building's plan with these variants and its own bindings. A fixed draw, so the same
     * selection plans the same cells each time it is asked.
     */
    private static @Nullable BuildPlan plan(Blueprint bp, Structure structure, Map<String, String> variants) {
        Random random = new Random(structure.id().getLeastSignificantBits());
        return Planner.plan(bp, Blueprints.dictionary(), Placer.SUPPORT, structure.bindings(), variants,
                Chooser.random(random), random, new Diagnostics());
    }

    private static @Nullable Blueprint blueprint(Structure structure) {
        return Blueprints.find(structure.blueprint()).map(entry -> entry.compiled().blueprint()).orElse(null);
    }

    private static boolean inside(Footprint f, Pos at) {
        return at.x() >= f.minX() && at.x() <= f.maxX() && at.z() >= f.minZ() && at.z() <= f.maxZ();
    }
}
