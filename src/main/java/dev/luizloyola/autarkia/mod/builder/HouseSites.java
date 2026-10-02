package dev.luizloyola.autarkia.mod.builder;

import dev.luizloyola.anima.compat.nav.LevelGrid;
import dev.luizloyola.anima.compat.terrain.GroundReader;
import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.brain.sense.DangerField;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.sense.SetbackField;
import dev.luizloyola.anima.core.nav.MoveCapabilities;
import dev.luizloyola.anima.core.nav.Path;
import dev.luizloyola.anima.core.nav.Pathfinder;
import dev.luizloyola.anima.core.nav.Waypoint;
import dev.luizloyola.anima.core.social.PartyId;
import dev.luizloyola.anima.core.social.PlaceRow;
import dev.luizloyola.anima.core.store.Store;
import dev.luizloyola.anima.core.terrain.GroundSample;
import dev.luizloyola.anima.core.terrain.Terrain;
import dev.luizloyola.anima.core.territory.ChunkKey;
import dev.luizloyola.anima.core.territory.Claimed;
import dev.luizloyola.anima.core.territory.Reason;
import dev.luizloyola.anima.mod.nav.PathfinderService;
import dev.luizloyola.anima.mod.social.PlacesData;
import dev.luizloyola.anima.mod.territory.Territories;
import dev.luizloyola.autarkia.compat.bp.Placer;
import dev.luizloyola.autarkia.core.board.SetUp;
import dev.luizloyola.autarkia.core.bp.Blueprint;
import dev.luizloyola.autarkia.core.bp.BuildPlan;
import dev.luizloyola.autarkia.core.bp.Chooser;
import dev.luizloyola.autarkia.core.bp.Diagnostics;
import dev.luizloyola.autarkia.core.bp.Placement;
import dev.luizloyola.autarkia.core.bp.Planner;
import dev.luizloyola.autarkia.core.bp.Stock;
import dev.luizloyola.autarkia.core.builder.HouseSite;
import dev.luizloyola.autarkia.core.builder.Structure;
import dev.luizloyola.autarkia.core.config.AutarkiaConfig;
import dev.luizloyola.autarkia.mod.bp.Blueprints;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.ThreadLocalRandom;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The house-site chooser over the live world: the ground round a party's area, the party's
 * stations treated as movable (decision 10), growth priced by the territory and the walk to the
 * door by the pathfinder. Server thread only.
 */
public final class HouseSites {

    private static final Logger LOGGER = LoggerFactory.getLogger("autarkia/house_site");

    /** How far past the area the ground is read: the ring, a pad's reach past its footprint, the view. */
    private static final int READ_MARGIN = HouseSite.RING + 16;
    /** How near the door a walk must end, in blocks along each axis. */
    private static final int NEAR_ENOUGH = 2;

    /** What the base lines put down at HOME: the stations a house may stand over, once moved. */
    private static final Set<PoiKind> STATIONS = Set.of(SetUp.WORKBENCH.kind(), SetUp.STORE.kind(),
            SetUp.FURNACE.kind());

    private HouseSites() {
    }

    /** The ground and the party a choice for {@code party} is made over, or null with no area. */
    public record Inputs(Terrain ground, HouseSite.Party party) {
    }

    /** How many sites are refined in full: the dear part, so a few, but enough to differ. */
    public static final int SHORTLIST = 16;

    /**
     * The placements a blueprint allows, narrowed to {@code facing} when one is named and to the
     * mirrored ones when {@code flip}.
     */
    public static List<Placement> placements(Blueprint bp, Blueprint.@Nullable Facing facing, boolean flip) {
        List<Placement> placements = new ArrayList<>();
        for (Blueprint.Facing f : Blueprint.Facing.values()) {
            if (!bp.headers().orientation().contains(f) || (facing != null && facing != f)) {
                continue;
            }
            for (boolean mirrored : new boolean[] {false, true}) {
                if ((!mirrored || bp.headers().flippable()) && (!flip || mirrored)) {
                    placements.add(new Placement(f, mirrored));
                }
            }
        }
        return placements;
    }

    /** The plan's sites over these inputs, best first. */
    public static HouseSite.Result choose(Inputs inputs, BuildPlan plan, List<Placement> placements) {
        return HouseSite.choose(inputs.ground(), HouseSite.Shape.of(plan, Blueprints.dictionary(), placements),
                inputs.party(), HouseSite.Weights.DEFAULTS, SHORTLIST);
    }

    /** What taking a site did: the growth, and the record when the area could take it. */
    public record Applied(Claimed grown, @Nullable Structure structure) {
    }

    /**
     * Takes {@code choice}: the area grows by its footprint and margin and the party records it,
     * sited. Its ground is cleared by the area line and its pad then flattened ({@link Structures}).
     */
    public static Applied apply(MinecraftServer server, PartyId party, String blueprint, BuildPlan plan,
                                HouseSite.Choice choice, String by) {
        String at = "(" + choice.anchorX() + ", " + choice.y() + ", " + choice.anchorZ() + ")";
        Claimed grown = Territories.of(server).grow(party, choice.footprint().chunks(ChunkKey.OVERWORLD),
                Territories.margin(), Reason.of(Reason.Kind.GROW, blueprint + " at " + at + ", sited by " + by),
                Territories.now(server));
        if (!grown.granted()) {
            return new Applied(grown, null);
        }
        Structure structure = new Structure(java.util.UUID.randomUUID(), blueprint, plan.version(), plan.variants(),
                plan.bindings(), new Pos(choice.anchorX(), choice.y(), choice.anchorZ()), choice.shape().placement(),
                choice.built(), choice.pad(), Structure.Phase.SITED, server.overworld().getGameTime(), "");
        StructuresData.get(server).add(party, structure);
        return new Applied(grown, structure);
    }

    /**
     * A plan of {@code bp} with these variants and no pinned materials, each slot bound to what
     * {@code stock} has most of, or null with why in {@code out}. A random binding waited on dark
     * oak in a jungle for good (2026-10-01).
     */
    public static @Nullable BuildPlan plan(Blueprint bp, Map<String, String> variants, Stock stock, Diagnostics out) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        return Planner.plan(bp, Blueprints.dictionary(), Placer.SUPPORT, Map.of(), variants,
                Chooser.stocked(stock, Chooser.random(random)), random, out);
    }

    public static Inputs inputs(ServerLevel level, PartyId party) {
        MinecraftServer server = level.getServer();
        SortedSet<ChunkKey> area = new TreeSet<>();
        for (ChunkKey chunk : Territories.of(server).area(party)) {
            if (chunk.dimension().equals(ChunkKey.OVERWORLD)) {
                area.add(chunk);
            }
        }
        if (area.isEmpty()) {
            return null;
        }
        List<Pos> stores = new ArrayList<>();
        List<Pos> places = new ArrayList<>();
        for (PlaceRow row : PlacesData.get(server).places().rows()) {
            if (party.equals(row.party()) && STATIONS.contains(row.kind())
                    && area.contains(ChunkKey.at(ChunkKey.OVERWORLD, row.at().x(), row.at().z()))) {
                places.add(row.at());
                if (row.kind().equals(Store.POI)) {
                    stores.add(row.at());
                }
            }
        }
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (ChunkKey chunk : area) {
            minX = Math.min(minX, chunk.minBlockX());
            minZ = Math.min(minZ, chunk.minBlockZ());
            maxX = Math.max(maxX, chunk.maxBlockX());
            maxZ = Math.max(maxZ, chunk.maxBlockZ());
        }
        GroundSample sample = GroundReader.read(level, minX - READ_MARGIN, minZ - READ_MARGIN, maxX + READ_MARGIN,
                maxZ + READ_MARGIN);
        for (Pos place : places) {
            sample.unflag(place.x(), place.z(), GroundSample.USED);
        }
        Terrain ground = Terrain.analyse(sample, HouseSite.groundRules());
        List<HouseSite.Planned> planned = new ArrayList<>();
        for (Structure structure : StructuresData.get(server).of(party)) {
            planned.add(new HouseSite.Planned(structure.built(), structure.pad()));
        }
        return new Inputs(ground, new Live(level, party, area, stores, places, planned, ground));
    }

    /** The party as the world has it now. */
    private record Live(ServerLevel level, PartyId id, SortedSet<ChunkKey> area, List<Pos> stores, List<Pos> places,
                        List<HouseSite.Planned> planned, Terrain ground) implements HouseSite.Party {

        @Override
        public OptionalInt growth(SortedSet<ChunkKey> footprint) {
            MinecraftServer server = level.getServer();
            Claimed priced = Territories.of(server).planGrow(id, footprint, Territories.margin(),
                    Reason.of(Reason.Kind.GROW, "a house site, priced"), Territories.now(server));
            return priced.granted() ? OptionalInt.of(priced.added().size()) : OptionalInt.empty();
        }

        /**
         * A walk from beside the store to the ground before the door, on today's ground, with no
         * blocks laid: a route that needs a bridge is no route to a front door. One that ends
         * within {@value #NEAR_ENOUGH} of the door counts — what stands there is cleared before
         * anything is built, and in a wood the doorstep's own column is often a trunk.
         */
        @Override
        public OptionalInt route(Pos from, Pos to) {
            MoveCapabilities body = MoveCapabilities.of(AutarkiaConfig.PERSON);
            LevelGrid grid = new LevelGrid(level);
            BlockPos start = standBeside(grid, body, from);
            if (start == null || to.x() < ground.minX() || to.z() < ground.minZ()
                    || to.x() >= ground.minX() + ground.width() || to.z() >= ground.minZ() + ground.depth()) {
                LOGGER.info("house site: no route to {} — {}", to, start == null ? "nowhere to stand by " + from
                        : "the door is off the read");
                return OptionalInt.empty();
            }
            BlockPos goal = new BlockPos(to.x(), ground.ground(to.x(), to.z()) + 1, to.z());
            Path path = PathfinderService.computeNow(level, null, start, goal, body, DangerField.NONE,
                    SetbackField.NONE).result().join();
            boolean near = path != null && !path.isEmpty()
                    && Math.abs(path.last().x() - goal.getX()) <= NEAR_ENOUGH
                    && Math.abs(path.last().z() - goal.getZ()) <= NEAR_ENOUGH;
            if (path == null || !(path.reachedGoal() || near) || path.laid() > 0) {
                LOGGER.info("house site: no route {} -> {} — {}", start.toShortString(), goal.toShortString(),
                        path == null ? "no path" : !path.reachedGoal() ? "stopped short at " + (path.isEmpty()
                                ? "the start" : path.last()) : path.laid() + " blocks to lay");
                return OptionalInt.empty();
            }
            double length = 0;
            Waypoint last = null;
            for (Waypoint step : path.waypoints()) {
                if (last != null) {
                    length += Math.hypot(step.x() - last.x(), step.z() - last.z());
                }
                last = step;
            }
            return OptionalInt.of((int) Math.round(length));
        }

        /**
         * A cell a body can stand in by the store: beside it first, then a ring further, each a
         * block up or down — a chest is often set on a step, or on top of something.
         */
        private static BlockPos standBeside(LevelGrid grid, MoveCapabilities body, Pos at) {
            for (int ring = 1; ring <= 2; ring++) {
                for (int dy : new int[] {0, -1, 1}) {
                    for (int dx = -ring; dx <= ring; dx++) {
                        for (int dz = -ring; dz <= ring; dz++) {
                            if (Math.max(Math.abs(dx), Math.abs(dz)) == ring
                                    && Pathfinder.standable(grid, body, at.x() + dx, at.y() + dy, at.z() + dz)) {
                                return new BlockPos(at.x() + dx, at.y() + dy, at.z() + dz);
                            }
                        }
                    }
                }
            }
            return null;
        }
    }
}
