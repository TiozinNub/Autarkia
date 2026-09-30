package dev.luizloyola.autarkia.core.direction;

import dev.luizloyola.anima.core.terrain.Landscape;
import dev.luizloyola.anima.core.terrain.Terrain;
import dev.luizloyola.anima.core.terrain.TerrainRules;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What a place is worth to live in: what lasts around it, never the work to make it ready
 * (docs/superpowers/specs/2026-09-25-home-search-design.md, decisions 1 and 5). A candidate is a
 * footprint the site query allows; its value is the sum over the wants of each one's worth, falling
 * off with the distance from the plot's closest block to the want's nearest instance.
 *
 * <p>Each want counts once, for its nearest instance, or a forest would make every plot in it worth
 * thousands. Water, lava and room are read from the ground; stone and bee nests from what the
 * settler knows, since it judges by what it has seen.
 */
public final class HomeJudge {

    public enum Want {
        WATER, STONE, ROOM, LAVA, BEE;

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * One want's terms: full worth within {@code near} of the plot's edge, falling linearly to
     * nothing at {@code far}; an instance smaller than {@code min} does not count.
     */
    public record Terms(double worth, int near, int far, int min) {

        double falloff(double distance) {
            if (distance <= near) {
                return 1;
            }
            return Math.max(0, Math.min(1, (far - distance) / Math.max(1e-9, far - near)));
        }
    }

    /** The whole table, one set for the search (decision 4). */
    public record Table(int size, int readRadius, double barStart, double barStep, Terms water,
                        Terms stone, Terms lava, Terms bee, double roomWorth, int roomRadius,
                        int roomFull, int lavaRefuse) {

        public Table {
            size |= 1;
        }

        public static Table configured() {
            return new Table(HomeKnob.SIZE.i(), HomeKnob.READ_RADIUS.i(), HomeKnob.BAR_START.d(),
                    HomeKnob.BAR_STEP.d(),
                    terms(HomeKnob.WATER_WORTH, HomeKnob.WATER_NEAR, HomeKnob.WATER_FAR, HomeKnob.WATER_MIN),
                    terms(HomeKnob.STONE_WORTH, HomeKnob.STONE_NEAR, HomeKnob.STONE_FAR, HomeKnob.STONE_MIN),
                    terms(HomeKnob.LAVA_WORTH, HomeKnob.LAVA_NEAR, HomeKnob.LAVA_FAR, HomeKnob.LAVA_MIN),
                    terms(HomeKnob.BEE_WORTH, HomeKnob.BEE_NEAR, HomeKnob.BEE_FAR, HomeKnob.BEE_MIN),
                    HomeKnob.ROOM_WORTH.d(), HomeKnob.ROOM_RADIUS.i(), HomeKnob.ROOM_FULL.i(),
                    HomeKnob.LAVA_REFUSE.i());
        }

        private static Terms terms(HomeKnob worth, HomeKnob near, HomeKnob far, HomeKnob min) {
            return new Terms(worth.d(), near.i(), far.i(), min.i());
        }

        /**
         * What a place must be worth after {@code legs} legs walked since the first candidate:
         * the longer the walk, the less picky.
         */
        public double bar(int legs) {
            return barStart - barStep * legs;
        }

        public Table withReadRadius(int radius) {
            return new Table(size, radius, barStart, barStep, water, stone, lava, bee, roomWorth,
                    roomRadius, roomFull, lavaRefuse);
        }

        /** The terrain rules in force, judging footprints of the plot's size. */
        public TerrainRules rules(TerrainRules base) {
            return new TerrainRules(base.smoothRadius(), base.maxSlope(), base.maxRough(),
                    base.steepAngle(), base.steepHeight(), base.steepAboveLand(), base.cliffHeight(),
                    base.areaSize(), base.usedMargin(), size, base.maxTilt(), base.treeCost());
        }

        /**
         * How far past the read radius the ground must be read for every want to see its whole
         * reach, before the terrain rules' own margin.
         */
        public int margin() {
            return Math.max(size / 2 + Math.max(water.far(), lava.far()), roomRadius);
        }
    }

    /**
     * Something the settler knows of, for a want its eyes supply: where it is, and how big —
     * {@link #GLIMPSED} for a far sighting, which counts at full worth (decision 18).
     */
    public record Known(int x, int z, int units) {
        public static final int GLIMPSED = -1;
    }

    /**
     * One want's part: how far its nearest instance lies from the plot's edge (for room, how many
     * flat columns there are), infinite with none; and the points it gives.
     */
    public record Line(Want want, double measure, double points) {
    }

    /** An allowed plot, centred on {@code (x, z)}, with {@code y} the ground at its centre. */
    public record Candidate(int x, int z, int y, int size, double value, List<Line> lines) {

        public Candidate {
            lines = List.copyOf(lines);
        }

        /** Whether two plots share a column. */
        public boolean overlaps(Candidate other) {
            int reach = (size + other.size) / 2;
            return Math.abs(x - other.x) < reach && Math.abs(z - other.z) < reach;
        }
    }

    private HomeJudge() {
    }

    /**
     * Every plot the ground allows with its centre within the read radius of {@code (x, z)}, best
     * first; between equals, the nearer. Lava too close refuses a plot (decision 8).
     */
    public static List<Candidate> judge(Landscape land, int x, int z, Table table,
                                        Map<Want, List<Known>> known) {
        Terrain terrain = land.terrain();
        if (terrain.footprint() != table.size()) {
            throw new IllegalArgumentException("terrain judged " + terrain.footprint()
                    + "-wide footprints, the plot is " + table.size() + "; see Table.rules");
        }
        int r = table.readRadius();
        int h = table.size() / 2;
        List<Ranked> found = new ArrayList<>();
        for (int cz = z - r; cz <= z + r; cz++) {
            for (int cx = x - r; cx <= x + r; cx++) {
                long dx = cx - x;
                long dz = cz - z;
                if (dx * dx + dz * dz > (long) r * r || !inside(terrain, cx, cz, h)
                        || !terrain.allowed(cx, cz)) {
                    continue;
                }
                double lava = land.toFluid(Landscape.Fluid.LAVA, table.lava().min(), table.size(), cx, cz);
                if (lava <= table.lavaRefuse()) {
                    continue;
                }
                List<Line> lines = new ArrayList<>(Want.values().length);
                double water = land.toFluid(Landscape.Fluid.WATER, table.water().min(), table.size(), cx, cz);
                lines.add(new Line(Want.WATER, water, table.water().worth() * table.water().falloff(water)));
                lines.add(nearest(Want.STONE, table.stone(), known, cx, cz, h));
                int room = land.flatAround(cx, cz, table.roomRadius(), table.size());
                lines.add(new Line(Want.ROOM, room,
                        table.roomWorth() * Math.min(1.0, (double) room / table.roomFull())));
                lines.add(new Line(Want.LAVA, lava, table.lava().worth() * table.lava().falloff(lava)));
                lines.add(nearest(Want.BEE, table.bee(), known, cx, cz, h));
                double value = 0;
                for (Line line : lines) {
                    value += line.points();
                }
                found.add(new Ranked(new Candidate(cx, cz, terrain.ground(cx, cz), table.size(), value,
                        lines), Math.hypot(dx, dz)));
            }
        }
        found.sort(Comparator.comparingDouble((Ranked each) -> -each.candidate().value())
                .thenComparingDouble(Ranked::away));
        List<Candidate> out = new ArrayList<>(found.size());
        for (Ranked each : found) {
            out.add(each.candidate());
        }
        return out;
    }

    private record Ranked(Candidate candidate, double away) {
    }

    /** The first {@code n} of {@code ranked} that share no column with a better one. */
    public static List<Candidate> apart(List<Candidate> ranked, int n) {
        List<Candidate> out = new ArrayList<>();
        for (Candidate candidate : ranked) {
            if (out.size() == n) {
                break;
            }
            if (out.stream().noneMatch(candidate::overlaps)) {
                out.add(candidate);
            }
        }
        return out;
    }

    /** A plot's breakdown by want, for a reader. */
    public static Map<Want, Line> byWant(Candidate candidate) {
        Map<Want, Line> out = new EnumMap<>(Want.class);
        for (Line line : candidate.lines()) {
            out.put(line.want(), line);
        }
        return out;
    }

    private static boolean inside(Terrain terrain, int x, int z, int h) {
        return x - h >= terrain.minX() && z - h >= terrain.minZ()
                && x + h < terrain.minX() + terrain.width() && z + h < terrain.minZ() + terrain.depth();
    }

    private static Line nearest(Want want, Terms terms, Map<Want, List<Known>> known, int x, int z,
                                int h) {
        double best = Double.POSITIVE_INFINITY;
        for (Known each : known.getOrDefault(want, List.of())) {
            if (each.units() != Known.GLIMPSED && each.units() < terms.min()) {
                continue;
            }
            // From the plot's closest column.
            double dx = Math.max(0, Math.abs(each.x() - x) - h);
            double dz = Math.max(0, Math.abs(each.z() - z) - h);
            best = Math.min(best, Math.hypot(dx, dz));
        }
        return new Line(want, best, terms.worth() * terms.falloff(best));
    }
}
