package dev.luizloyola.autarkia.core.board;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.brain.knowledge.Sighting;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.AchieveTask;
import dev.luizloyola.anima.core.brain.task.GoTo;
import dev.luizloyola.anima.core.brain.task.LookRound;
import dev.luizloyola.anima.core.brain.task.Method;
import dev.luizloyola.anima.core.brain.task.Task;
import dev.luizloyola.anima.core.inv.ItemSpec;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * An expedition's search for a source nobody knows: out along one heading, {@link #LEG} blocks a
 * leg, looking round at every stop, and straight to any glimpse of the wanted kind. Done once the
 * body knows a way within {@link Expedition#REACH}; failed when the legs run out.
 */
public final class SeekSource implements AchieveTask {

    /** One leg — the HOME search's {@code home.leg_length}. */
    public static final int LEG = 64;

    /** Within this of a glimpse it has been walked to: the near field takes over. */
    static final double AT_GLIMPSE = 8;

    private final PoiKind kind;
    private final ItemSpec resource;
    private final Set<String> pursued;
    private final int heading;
    private int legsLeft;
    private int walked;
    private boolean looked;
    /** Glimpses already walked to, so one that led nowhere is not walked to again. */
    private final Set<Pos> visited;
    private final List<Method> methods = List.of(new ToGlimpse(), new Look(), new Leg());

    public SeekSource(PoiKind kind, ItemSpec resource, Set<String> pursued, int heading, int legs) {
        this(kind, resource, pursued, heading, legs, 0, false, Set.of());
    }

    public SeekSource(PoiKind kind, ItemSpec resource, Set<String> pursued, int heading, int legsLeft,
                      int walked, boolean looked, Set<Pos> visited) {
        this.kind = kind;
        this.resource = resource;
        this.pursued = Set.copyOf(pursued);
        this.heading = Math.floorMod(heading, 8);
        this.legsLeft = legsLeft;
        this.walked = walked;
        this.looked = looked;
        this.visited = new LinkedHashSet<>(visited);
    }

    @Override
    public boolean satisfied(BrainContext ctx) {
        return Expedition.knowsAWay(resource, pursued, ctx);
    }

    /** Legs walked and stops looked from: a search that is getting somewhere is not stalled. */
    @Override
    public double progress(BrainContext ctx) {
        return walked * 2 + (looked ? 1 : 0) + visited.size();
    }

    @Override
    public List<Method> methods() {
        return methods;
    }

    @Override
    public String describe() {
        return "look for " + resource.name() + " to the " + HEADINGS[heading];
    }

    static final String[] HEADINGS = {"east", "south-east", "south", "south-west", "west", "north-west",
            "north", "north-east"};

    /** The unit step of a heading, east first and clockwise, as the world's x and z run. */
    static int[] step(int heading) {
        double angle = Math.toRadians(45.0 * Math.floorMod(heading, 8));
        return new int[] {(int) Math.round(Math.cos(angle)), (int) Math.round(Math.sin(angle))};
    }

    /** The heading nearest the way from {@code from} to {@code to}. */
    static int headingTo(Pos from, Pos to) {
        double angle = Math.toDegrees(Math.atan2(to.z() - from.z(), to.x() - from.x()));
        return Math.floorMod((int) Math.round(angle / 45.0), 8);
    }

    private Optional<Sighting> glimpse(BrainContext ctx) {
        Pos here = ctx.percepts().position();
        Sighting best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (Sighting sighting : ctx.knowledge().glimpses(kind)) {
            double distance = Math.hypot(sighting.at().x() - here.x(), sighting.at().z() - here.z());
            if (!visited.contains(sighting.at()) && distance > AT_GLIMPSE && distance < bestDistance
                    && distance <= Expedition.REACH) {
                best = sighting;
                bestDistance = distance;
            }
        }
        return Optional.ofNullable(best);
    }

    private static GoTo walkTo(BrainContext ctx, int x, int z) {
        int top = ctx.percepts().blocks().topY(x, z);
        int y = top == Integer.MIN_VALUE ? ctx.percepts().position().y() : top + 1;
        return new GoTo(x, y, z);
    }

    /** A glimpse of the kind it wants: straight there. */
    private final class ToGlimpse implements Method {
        @Override
        public boolean applicable(BrainContext ctx) {
            return glimpse(ctx).isPresent();
        }

        @Override
        public double estimateCost(BrainContext ctx) {
            return 0;
        }

        @Override
        public List<Task> decompose(BrainContext ctx) {
            Pos at = glimpse(ctx).orElseThrow().at();
            visited.add(at);
            looked = false;
            return List.of(walkTo(ctx, at.x(), at.z()));
        }

        @Override
        public String describe() {
            return "go to what was glimpsed";
        }
    }

    /** At a new stop, a look all the way round. */
    private final class Look implements Method {
        @Override
        public boolean applicable(BrainContext ctx) {
            return !looked;
        }

        @Override
        public double estimateCost(BrainContext ctx) {
            return 1;
        }

        @Override
        public List<Task> decompose(BrainContext ctx) {
            looked = true;
            return List.of(new LookRound());
        }

        @Override
        public String describe() {
            return "look round";
        }
    }

    /** On along the heading. */
    private final class Leg implements Method {
        @Override
        public boolean applicable(BrainContext ctx) {
            return legsLeft > 0;
        }

        @Override
        public double estimateCost(BrainContext ctx) {
            return 2;
        }

        @Override
        public List<Task> decompose(BrainContext ctx) {
            legsLeft--;
            walked++;
            looked = false;
            Pos here = ctx.percepts().position();
            int[] step = step(heading);
            return List.of(walkTo(ctx, here.x() + step[0] * LEG, here.z() + step[1] * LEG));
        }

        @Override
        public String describe() {
            return "walk on " + HEADINGS[heading];
        }
    }

    // ── continuity ───────────────────────────────────────────────────────────────────────────

    public PoiKind kind() {
        return kind;
    }

    public ItemSpec resource() {
        return resource;
    }

    public Set<String> pursued() {
        return pursued;
    }

    public int heading() {
        return heading;
    }

    public int legsLeft() {
        return legsLeft;
    }

    public int walked() {
        return walked;
    }

    public boolean looked() {
        return looked;
    }

    public List<Pos> visited() {
        return new ArrayList<>(visited);
    }
}
