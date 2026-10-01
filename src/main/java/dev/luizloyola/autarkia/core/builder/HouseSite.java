package dev.luizloyola.autarkia.core.builder;

import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.terrain.Terrain;
import dev.luizloyola.anima.core.terrain.Terrain.Kind;
import dev.luizloyola.anima.core.terrain.TerrainRules;
import dev.luizloyola.anima.core.territory.ChunkKey;
import dev.luizloyola.autarkia.core.bp.BuildPlan;
import dev.luizloyola.autarkia.core.bp.Dictionary;
import dev.luizloyola.autarkia.core.bp.Footprint;
import dev.luizloyola.autarkia.core.bp.Placement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.SortedSet;

/**
 * Where a party's next building goes (docs/superpowers/specs/2026-10-01-house-site-design.md):
 * every placement of a plan in or beside the home area, refused for what it may not stand on, then
 * priced. Two passes: masks and a cheap price over every placement, then the best few priced in
 * full. Pure — the world comes in as a {@link Terrain} read with no used margin, and a
 * {@link Party}.
 */
public final class HouseSite {

    /** Why a placement was refused, for the readout and the paint. */
    public enum Refusal {
        /** Unknown, fluid or built ground under the pad. */
        GROUND,
        /** The area could not grow to take it in one piece. */
        GROWTH,
        /** Touching something built. */
        PADDING,
        /** Lava near enough to set a wooden house alight. */
        LAVA,
        /** Over the pad of a building already sited. */
        SITED,
        /** No walk from the stores to the door. */
        NO_ROUTE
    }

    /** At least this many free blocks between a building and anything built (decision 5). */
    static final int MIN_GAP = 1;
    /** And from this many on, the gap costs nothing. */
    static final int FREE_GAP = 5;
    /** How near lava may come to the pad. Vanilla lava lights flammable blocks about two away. */
    static final int LAVA_GAP = 4;
    /** Cells of the apron in front of the doorstep, the doorstep included. */
    static final int APRON = 2;
    /** Cells in front of the door that should be open, level ground. */
    static final int VIEW = 5;
    /** How far past the area a footprint may stand: its margin ring. */
    public static final int RING = 16;

    /**
     * What each term costs. {@code earthwork} is per block moved; {@code newChunk} per chunk the
     * area grows by (low, decision 4); {@code padding} at the smallest gap, falling to nothing at
     * {@value #FREE_GAP}; {@code facing} with the door turned right away from the stores;
     * {@code route} per block walked; {@code view} per bad cell before the door; {@code roomLeft}
     * per block of side the area's largest free square loses; {@code inTheWay} per line crossed;
     * {@code baseMoved} per station of the party's the pad covers, which has to be moved first
     * (decision 10).
     */
    public record Weights(double earthwork, double newChunk, double padding, double facing, double route,
                          double view, double roomLeft, double inTheWay, double baseMoved) {
        public static final Weights DEFAULTS = new Weights(1, 2, 20, 10, 0.5, 3, 2, 15, 10);
    }

    /** What the chooser may ask of the party and the world beyond the ground. */
    public interface Party {
        /** The party's area, the chunks HOME holds. */
        SortedSet<ChunkKey> area();

        /** How many chunks the area must grow by to take this footprint, or empty when it cannot. */
        OptionalInt growth(SortedSet<ChunkKey> footprint);

        /** The party's stores at HOME. */
        List<Pos> stores();

        /**
         * Everything the party has set up at HOME — stores, stations — that a walk goes to. A site
         * may cover them (decision 10); the ground under them must then not read as used.
         */
        List<Pos> places();

        /** How many blocks a walk from {@code from} to {@code to} takes, or empty with no way. */
        OptionalInt route(Pos from, Pos to);

        /**
         * The buildings already sited and not yet built: each the same as built ground to a new
         * one — its walls keep the gap, its pad is nobody else's.
         */
        default List<Planned> planned() {
            return List.of();
        }
    }

    /** A sited building: its walls and its pad, in the world. */
    public record Planned(Footprint built, Footprint pad) {
    }

    /**
     * One placement of the plan, relative to its anchor: the footprint, which is the whole drawing;
     * what is built, the blocks' own bounds, which padding is measured from — a drawing may keep a
     * border of untouched cells round its walls; the pad the ground is levelled over — the
     * footprint, the stands on the ground round it, and the apron before the door; and the doorstep
     * with the way out of it.
     */
    public record Shape(Placement placement, Footprint footprint, Footprint built, Footprint pad, int doorX,
                        int doorLayer, int doorZ, int outX, int outZ) {

        /** Every allowed placement of a plan with a door; a plan with none has no shape. */
        public static List<Shape> of(BuildPlan plan, Dictionary dict, List<Placement> placements) {
            BuildOrder.Result proved = BuildOrder.prove(plan, dict);
            List<Shape> shapes = new ArrayList<>();
            for (Placement placement : placements) {
                of(plan, dict, placement, proved).ifPresent(shapes::add);
            }
            return shapes;
        }

        static Optional<Shape> of(BuildPlan plan, Dictionary dict, Placement placement, BuildOrder.Result proved) {
            Optional<int[]> step = Footprint.doorstep(plan, placement, dict);
            if (step.isEmpty()) {
                return Optional.empty();
            }
            Footprint footprint = Footprint.of(0, 0, plan, placement);
            int[] door = step.get();
            int[] out = outward(footprint, door[0], door[2]);
            int width = placement.width(plan.width(), plan.depth());
            int depth = placement.depth(plan.width(), plan.depth());
            int minX = Math.min(footprint.minX(), door[0] + out[0] * (APRON - 1));
            int minZ = Math.min(footprint.minZ(), door[2] + out[1] * (APRON - 1));
            int maxX = Math.max(footprint.maxX(), door[0] + out[0] * (APRON - 1));
            int maxZ = Math.max(footprint.maxZ(), door[2] + out[1] * (APRON - 1));
            for (BuildOrder.Placed placed : proved.order()) {
                Cell stand = placed.stand();
                boolean outside = stand.x() < 0 || stand.z() < 0 || stand.x() >= plan.width()
                        || stand.z() >= plan.depth();
                if (!outside) {
                    continue;
                }
                int[] cell = placement.cell(stand.x(), stand.z(), plan.width(), plan.depth());
                int x = Placement.offset(cell[0], width);
                int z = Placement.offset(cell[1], depth);
                minX = Math.min(minX, x);
                minZ = Math.min(minZ, z);
                maxX = Math.max(maxX, x);
                maxZ = Math.max(maxZ, z);
            }
            int[] built = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
            plan.forEach(placement, (dx, layer, dz, kind, state) -> {
                if (kind == BuildPlan.CellKind.BLOCK) {
                    built[0] = Math.min(built[0], dx);
                    built[1] = Math.min(built[1], dz);
                    built[2] = Math.max(built[2], dx);
                    built[3] = Math.max(built[3], dz);
                }
            });
            return Optional.of(new Shape(placement, footprint, new Footprint(built[0], built[1], built[2], built[3]),
                    new Footprint(minX, minZ, maxX, maxZ), door[0], door[1], door[2], out[0], out[1]));
        }

        /** Away from the footprint across its nearest edge; a doorstep set back inside takes the nearest. */
        private static int[] outward(Footprint f, int x, int z) {
            if (x < f.minX()) {
                return new int[] {-1, 0};
            }
            if (x > f.maxX()) {
                return new int[] {1, 0};
            }
            if (z < f.minZ()) {
                return new int[] {0, -1};
            }
            if (z > f.maxZ()) {
                return new int[] {0, 1};
            }
            int west = x - f.minX();
            int east = f.maxX() - x;
            int north = z - f.minZ();
            int south = f.maxZ() - z;
            int least = Math.min(Math.min(west, east), Math.min(north, south));
            return least == west ? new int[] {-1, 0} : least == east ? new int[] {1, 0}
                    : least == north ? new int[] {0, -1} : new int[] {0, 1};
        }
    }

    /**
     * A site: where the anchor goes, at what height layer 0 sits once the pad is level, and what
     * each term cost.
     */
    public record Choice(int anchorX, int anchorZ, int y, Shape shape, double cost, Map<String, Double> terms) {

        public Footprint footprint() {
            return shift(shape.footprint(), anchorX, anchorZ);
        }

        public Footprint pad() {
            return shift(shape.pad(), anchorX, anchorZ);
        }

        public Footprint built() {
            return shift(shape.built(), anchorX, anchorZ);
        }

        /** The cell a body stands in to walk through the door. */
        public Pos doorstep() {
            return new Pos(anchorX + shape.doorX(), y + shape.doorLayer(), anchorZ + shape.doorZ());
        }
    }

    /** The best sites, cheapest first, and how many placements each refusal turned away. */
    public record Result(List<Choice> best, Map<Refusal, Integer> refused) {
    }

    private final Terrain ground;
    private final Party party;
    private final Weights weights;
    private final SortedSet<ChunkKey> area;
    private final long[] used;
    private final long[] lava;
    private final int w;
    private final int d;

    private HouseSite(Terrain ground, Party party, Weights weights) {
        this.ground = ground;
        this.party = party;
        this.weights = weights;
        this.area = party.area();
        this.w = ground.width();
        this.d = ground.depth();
        boolean[] usedMask = new boolean[w * d];
        boolean[] lavaMask = new boolean[w * d];
        for (int row = 0; row < d; row++) {
            for (int col = 0; col < w; col++) {
                int x = ground.minX() + col;
                int z = ground.minZ() + row;
                usedMask[row * w + col] = ground.kind(x, z) == Kind.USED;
                lavaMask[row * w + col] = ground.lava(x, z);
            }
        }
        for (Planned planned : party.planned()) {
            Footprint b = planned.built();
            for (int x = Math.max(b.minX(), ground.minX()); x <= Math.min(b.maxX(), ground.minX() + w - 1); x++) {
                for (int z = Math.max(b.minZ(), ground.minZ()); z <= Math.min(b.maxZ(), ground.minZ() + d - 1); z++) {
                    usedMask[(z - ground.minZ()) * w + x - ground.minX()] = true;
                }
            }
        }
        this.used = sat(usedMask, w, d);
        this.lava = sat(lavaMask, w, d);
    }

    /**
     * The configured terrain rules with no used margin: the chooser prices the gap to what is built
     * itself, down to one block, where the HOME search's margin of four would refuse it.
     */
    public static TerrainRules groundRules() {
        TerrainRules r = TerrainRules.configured();
        return new TerrainRules(r.smoothRadius(), r.maxSlope(), r.maxRough(), r.steepAngle(), r.steepHeight(),
                r.steepAboveLand(), r.cliffHeight(), r.areaSize(), 0, r.footprint(), r.maxTilt(), r.treeCost());
    }

    /**
     * The best {@code shortlist} sites for these shapes, cheapest first. The ground must cover the
     * area and a chunk round it, read with no used margin: the gap to what is built is priced here.
     */
    public static Result choose(Terrain ground, List<Shape> shapes, Party party, Weights weights, int shortlist) {
        return new HouseSite(ground, party, weights).choose(shapes, shortlist);
    }

    private Result choose(List<Shape> shapes, int shortlist) {
        Map<Refusal, Integer> refused = new EnumMap<>(Refusal.class);
        if (area.isEmpty()) {
            return new Result(List.of(), refused);
        }
        Footprint box = bounds(area);
        Map<SortedSet<ChunkKey>, OptionalInt> growths = new HashMap<>();
        List<Choice> cheap = new ArrayList<>();
        for (Shape shape : shapes) {
            Footprint pad = shape.pad();
            Terrain.Rects rects = ground.rects(pad.maxX() - pad.minX() + 1, pad.maxZ() - pad.minZ() + 1,
                    Double.POSITIVE_INFINITY);
            Footprint fp = shape.footprint();
            for (int ax = box.minX() - RING - fp.minX(); ax <= box.maxX() + RING - fp.maxX(); ax++) {
                for (int az = box.minZ() - RING - fp.minZ(); az <= box.maxZ() + RING - fp.maxZ(); az++) {
                    Footprint at = shift(fp, ax, az);
                    Footprint padAt = shift(pad, ax, az);
                    if (!rects.allowed(padAt.minX(), padAt.minZ())) {
                        refused.merge(Refusal.GROUND, 1, Integer::sum);
                        continue;
                    }
                    if (party.planned().stream().anyMatch(p -> overlap(p.pad(), padAt))) {
                        refused.merge(Refusal.SITED, 1, Integer::sum);
                        continue;
                    }
                    int gap = gap(shift(shape.built(), ax, az));
                    if (gap < MIN_GAP) {
                        refused.merge(Refusal.PADDING, 1, Integer::sum);
                        continue;
                    }
                    if (count(lava, grow(padAt, LAVA_GAP)) > 0) {
                        refused.merge(Refusal.LAVA, 1, Integer::sum);
                        continue;
                    }
                    OptionalInt added = growths.computeIfAbsent(at.chunks(ChunkKey.OVERWORLD), party::growth);
                    if (added.isEmpty()) {
                        refused.merge(Refusal.GROWTH, 1, Integer::sum);
                        continue;
                    }
                    Map<String, Double> terms = new LinkedHashMap<>();
                    terms.put("earthwork", weights.earthwork() * rects.estimate(padAt.minX(), padAt.minZ()));
                    terms.put("new_chunks", weights.newChunk() * added.getAsInt());
                    terms.put("padding", weights.padding() * (FREE_GAP - Math.min(gap, FREE_GAP))
                            / (double) (FREE_GAP - MIN_GAP));
                    terms.put("base_moved", weights.baseMoved() * covered(padAt));
                    // The height waits for the second pass: counted block by block, it is the dear part.
                    Choice choice = new Choice(ax, az, 0, shape, 0, terms);
                    terms.put("facing", weights.facing() * facing(choice));
                    cheap.add(priced(choice));
                }
            }
        }
        cheap.sort(Comparator.comparingDouble(Choice::cost));
        // Distinct sites: the same ground a block over is not a second answer.
        List<Choice> distinct = new ArrayList<>();
        for (Choice choice : cheap) {
            if (distinct.size() == shortlist) {
                break;
            }
            if (distinct.stream().noneMatch(kept -> overlap(kept.pad(), choice.pad()))) {
                distinct.add(choice);
            }
        }
        List<Choice> best = new ArrayList<>();
        int before = largestSquare(null);
        for (Choice choice : distinct) {
            Optional<Choice> full = refine(choice, before);
            if (full.isEmpty()) {
                refused.merge(Refusal.NO_ROUTE, 1, Integer::sum);
            } else {
                best.add(full.get());
            }
        }
        best.sort(Comparator.comparingDouble(Choice::cost));
        return new Result(List.copyOf(best), refused);
    }

    /** The second pass: the levelling counted, the walk to the door, the view from it, the room left. */
    private Optional<Choice> refine(Choice cheap, int roomBefore) {
        Footprint pad = cheap.pad();
        Map<String, Double> terms = new LinkedHashMap<>(cheap.terms());
        Terrain.Rect exact = ground.rects(pad.maxX() - pad.minX() + 1, pad.maxZ() - pad.minZ() + 1,
                Double.POSITIVE_INFINITY).fit(pad.minX(), pad.minZ()).orElseThrow();
        terms.put("earthwork", weights.earthwork() * exact.levelling());
        cheap = new Choice(cheap.anchorX(), cheap.anchorZ(), (int) Math.round(exact.y()), cheap.shape(), 0, terms);
        Optional<Pos> store = nearestStore(cheap.doorstep());
        if (store.isPresent()) {
            OptionalInt walk = party.route(store.get(), cheap.doorstep());
            if (walk.isEmpty()) {
                return Optional.empty();
            }
            terms.put("route", weights.route() * walk.getAsInt());
            terms.put("in_the_way", weights.inTheWay() * inTheWay(cheap.footprint(), store.get()));
        }
        terms.put("view", weights.view() * badView(cheap));
        terms.put("room_left", weights.roomLeft() * Math.max(0, roomBefore - largestSquare(pad)));
        return Optional.of(priced(cheap));
    }

    private static Choice priced(Choice choice) {
        double total = 0;
        for (double term : choice.terms().values()) {
            total += term;
        }
        return new Choice(choice.anchorX(), choice.anchorZ(), choice.y(), choice.shape(), total,
                Map.copyOf(choice.terms()));
    }

    // ---- terms ---------------------------------------------------------------------------------

    /** Free columns between what the plan builds and the nearest built column, up to {@value #FREE_GAP}. */
    private int gap(Footprint at) {
        for (int k = 1; k <= FREE_GAP; k++) {
            if (count(used, grow(at, k)) > 0) {
                return k - 1;
            }
        }
        return FREE_GAP;
    }

    /** How far the door turns from the nearest store: 0 facing it, 1 facing right away. */
    private double facing(Choice choice) {
        Pos step = choice.doorstep();
        Optional<Pos> store = nearestStore(step);
        if (store.isEmpty()) {
            return 0;
        }
        double dx = store.get().x() - step.x();
        double dz = store.get().z() - step.z();
        double length = Math.hypot(dx, dz);
        if (length == 0) {
            return 0;
        }
        double cos = (dx * choice.shape().outX() + dz * choice.shape().outZ()) / length;
        return Math.acos(Math.max(-1, Math.min(1, cos))) / Math.PI;
    }

    /** Cells in front of the door that are not open ground within a block of the pad. */
    private int badView(Choice choice) {
        Pos step = choice.doorstep();
        int bad = 0;
        for (int k = 1; k <= VIEW; k++) {
            int x = step.x() + choice.shape().outX() * k;
            int z = step.z() + choice.shape().outZ() * k;
            if (!inRead(x, z)) {
                bad++;
                continue;
            }
            Kind kind = ground.kind(x, z);
            boolean open = kind != Kind.UNKNOWN && kind != Kind.FLUID && kind != Kind.USED && kind != Kind.CLIFF
                    && !ground.covered(x, z) && Math.abs(ground.ground(x, z) - choice.y()) <= 1;
            if (!open) {
                bad++;
            }
        }
        return bad;
    }

    /** How many of the party's places the pad covers. */
    private int covered(Footprint pad) {
        int n = 0;
        for (Pos place : party.places()) {
            if (place.x() >= pad.minX() && place.x() <= pad.maxX() && place.z() >= pad.minZ()
                    && place.z() <= pad.maxZ()) {
                n++;
            }
        }
        return n;
    }

    /** How many of the party's places the footprint stands between the store and. */
    private int inTheWay(Footprint at, Pos store) {
        int crossed = 0;
        for (Pos place : party.places()) {
            if (!place.equals(store) && crosses(at, store, place)) {
                crossed++;
            }
        }
        return crossed;
    }

    /**
     * The side of the largest free square left in the area: unbuilt, dry, known ground, the pad
     * taken out when one is given.
     */
    private int largestSquare(Footprint pad) {
        Footprint box = bounds(area);
        int bw = box.maxX() - box.minX() + 1;
        int bd = box.maxZ() - box.minZ() + 1;
        int[] run = new int[bw * bd];
        int best = 0;
        for (int row = 0; row < bd; row++) {
            for (int col = 0; col < bw; col++) {
                int x = box.minX() + col;
                int z = box.minZ() + row;
                boolean free = area.contains(ChunkKey.at(ChunkKey.OVERWORLD, x, z)) && inRead(x, z)
                        && ground.kind(x, z) != Kind.USED && ground.kind(x, z) != Kind.FLUID
                        && ground.kind(x, z) != Kind.UNKNOWN
                        && (pad == null || x < pad.minX() || x > pad.maxX() || z < pad.minZ() || z > pad.maxZ());
                int i = row * bw + col;
                if (!free) {
                    run[i] = 0;
                } else if (row == 0 || col == 0) {
                    run[i] = 1;
                } else {
                    run[i] = 1 + Math.min(run[i - 1], Math.min(run[i - bw], run[i - bw - 1]));
                }
                best = Math.max(best, run[i]);
            }
        }
        return best;
    }

    private Optional<Pos> nearestStore(Pos from) {
        return party.stores().stream().min(Comparator.comparingDouble(
                store -> Math.hypot(store.x() - from.x(), store.z() - from.z())));
    }

    // ---- geometry ------------------------------------------------------------------------------

    static Footprint shift(Footprint f, int dx, int dz) {
        return new Footprint(f.minX() + dx, f.minZ() + dz, f.maxX() + dx, f.maxZ() + dz);
    }

    private static boolean overlap(Footprint a, Footprint b) {
        return a.minX() <= b.maxX() && b.minX() <= a.maxX() && a.minZ() <= b.maxZ() && b.minZ() <= a.maxZ();
    }

    private static Footprint grow(Footprint f, int by) {
        return new Footprint(f.minX() - by, f.minZ() - by, f.maxX() + by, f.maxZ() + by);
    }

    private static Footprint bounds(Set<ChunkKey> chunks) {
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (ChunkKey chunk : chunks) {
            minX = Math.min(minX, chunk.minBlockX());
            minZ = Math.min(minZ, chunk.minBlockZ());
            maxX = Math.max(maxX, chunk.maxBlockX());
            maxZ = Math.max(maxZ, chunk.maxBlockZ());
        }
        return new Footprint(minX, minZ, maxX, maxZ);
    }

    /** Whether the segment between two columns' centres passes over the rectangle. */
    static boolean crosses(Footprint f, Pos a, Pos b) {
        double ax = a.x() + 0.5;
        double az = a.z() + 0.5;
        double bx = b.x() + 0.5;
        double bz = b.z() + 0.5;
        double t0 = 0;
        double t1 = 1;
        double[] p = {-(bx - ax), bx - ax, -(bz - az), bz - az};
        double[] q = {ax - f.minX(), f.maxX() + 1 - ax, az - f.minZ(), f.maxZ() + 1 - az};
        for (int i = 0; i < 4; i++) {
            if (p[i] == 0) {
                if (q[i] < 0) {
                    return false;
                }
            } else {
                double t = q[i] / p[i];
                if (p[i] < 0) {
                    t0 = Math.max(t0, t);
                } else {
                    t1 = Math.min(t1, t);
                }
            }
        }
        return t0 <= t1;
    }

    private boolean inRead(int x, int z) {
        return x >= ground.minX() && z >= ground.minZ() && x < ground.minX() + w && z < ground.minZ() + d;
    }

    /** Columns marked in {@code s} inside {@code f}, clipped to the read. */
    private long count(long[] s, Footprint f) {
        int c0 = Math.max(0, f.minX() - ground.minX());
        int r0 = Math.max(0, f.minZ() - ground.minZ());
        int c1 = Math.min(w - 1, f.maxX() - ground.minX());
        int r1 = Math.min(d - 1, f.maxZ() - ground.minZ());
        if (c0 > c1 || r0 > r1) {
            return 0;
        }
        int stride = w + 1;
        return s[(r1 + 1) * stride + c1 + 1] - s[r0 * stride + c1 + 1] - s[(r1 + 1) * stride + c0] + s[r0 * stride + c0];
    }

    private static long[] sat(boolean[] mask, int w, int d) {
        long[] s = new long[(w + 1) * (d + 1)];
        for (int row = 0; row < d; row++) {
            for (int col = 0; col < w; col++) {
                int at = (row + 1) * (w + 1) + col + 1;
                int up = row * (w + 1) + col + 1;
                s[at] = (mask[row * w + col] ? 1 : 0) + s[at - 1] + s[up] - s[up - 1];
            }
        }
        return s;
    }
}
