package dev.luizloyola.autarkia.core.builder;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.autarkia.core.bp.Footprint;
import dev.luizloyola.autarkia.core.bp.Placement;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * A party's building, from the moment its site is chosen: the first form of the builder's record
 * (docs/superpowers/specs/2026-10-01-house-site-design.md, *After the choice*). It names the plan by
 * its blueprint, version, variants and bindings, which plan the same building again; the plan copy
 * itself waits for the build project.
 *
 * @param anchor    where the footprint's centre sits on layer 0, at the height the pad is levelled to
 * @param built     the bounds of its walls, in the world
 * @param pad       the ground levelled for it
 * @param note      why it was refused, or empty
 * @param work      who built it and when
 * @param grownFrom the variants it stood with before the growth under way, empty when none is
 */
public record Structure(UUID id, String blueprint, int version, Map<String, String> variants,
                        Map<Integer, String> bindings, Pos anchor, Placement placement, Footprint built,
                        Footprint pad, Phase phase, long sitedAt, String note, Work work,
                        Map<String, String> grownFrom) {

    /**
     * The building's making: the members who placed any of it, and the game times its build was
     * posted and finished, -1 until then. A game day is 24000 of them.
     */
    public record Work(List<AgentId> builders, long begunAt, long builtAt) {
        public static final Work NONE = new Work(List.of(), -1, -1);

        public Work {
            builders = List.copyOf(builders);
        }
    }

    public enum Phase {
        /** Chosen and its ground claimed; the felling round its pad goes on the board next. */
        SITED,
        /** The trees round its pad are being felled: that felling is on the board. */
        FELLING,
        /** The trees round its pad are felled; the clearing of its plants goes on the board next. */
        FELLED,
        /** The plants round its pad are being pulled up: that clearing is on the board. */
        CLEARING,
        /** Its plants are pulled up; the pad's flatten goes on the board next. */
        CLEARED,
        /** The pad's flatten is on the board. */
        LEVELLING,
        /** The pad is level; waiting for the builder. */
        LEVELLED,
        /** Its build is on the board. */
        BUILDING,
        /** It stands. */
        BUILT,
        /** It stands, and grows into {@link #variants} from {@link #grownFrom}: its diff is being built. */
        GROWING,
        /**
         * A quick one no longer wanted, the house standing: its taking down is on the board, and the
         * record goes once that is done (Luiz, 2026-10-21).
         */
        TAKING_DOWN,
        /** The pad cannot be levelled; {@link #note} says why. */
        REFUSED
    }

    /** How far above layer 0 its walls are taken to reach: the record keeps no height. */
    public static final int WALLS_HIGH = 16;

    /** How far past the pad a flatten eases the ground. */
    public static final int RING = dev.luizloyola.autarkia.core.earthwork.FlattenPlan.Rules.MAX_RING;

    /**
     * How far past the pad its trees are felled (Luiz, 2026-10-20: the trees near the site, not its
     * chunks'): the flatten's ring, since a trunk on a column the ring eases refuses the pad for good.
     * It takes in the builder's stands and scaffold too, which reach 5 past the walls.
     */
    public static final int FELL_PAST = RING;

    /** How far past the pad its plants are pulled up (Luiz, 2026-10-20: one or two blocks). */
    public static final int PLANTS_PAST = 2;

    /**
     * Blueprints that go up at once, the first nights' hut (Luiz, 2026-10-20): nobody fells their
     * ground first, so a pad with a trunk on it is refused, and they never stand over the base, so
     * nothing of it waits on them.
     */
    public static final Set<String> QUICK = Set.of("autarkia:dirt_hut");

    /** What a quick building's work bids: the shelter line's, so it goes before the base's and the area's. */
    public static final double QUICK_PRIORITY = 0.55;

    public Structure {
        Objects.requireNonNull(id, "id");
        variants = Map.copyOf(variants);
        bindings = Map.copyOf(bindings);
        note = note == null ? "" : note;
        work = work == null ? Work.NONE : work;
        grownFrom = grownFrom == null ? Map.of() : Map.copyOf(grownFrom);
    }

    public Structure(UUID id, String blueprint, int version, Map<String, String> variants,
                     Map<Integer, String> bindings, Pos anchor, Placement placement, Footprint built, Footprint pad,
                     Phase phase, long sitedAt, String note, Work work) {
        this(id, blueprint, version, variants, bindings, anchor, placement, built, pad, phase, sitedAt, note, work,
                Map.of());
    }

    public Structure(UUID id, String blueprint, int version, Map<String, String> variants,
                     Map<Integer, String> bindings, Pos anchor, Placement placement, Footprint built, Footprint pad,
                     Phase phase, long sitedAt, String note) {
        this(id, blueprint, version, variants, bindings, anchor, placement, built, pad, phase, sitedAt, note, Work.NONE);
    }

    /** Whether it stands, growing or not: lived in, its stations the party's. */
    /** Whether this goes up at once — see {@link #QUICK}. */
    public boolean quick() {
        return QUICK.contains(blueprint);
    }

    /** What this building's work bids: {@code usual}, or {@link #QUICK_PRIORITY} for a quick one. */
    public double bid(double usual) {
        return quick() ? QUICK_PRIORITY : usual;
    }

    public boolean stands() {
        return phase == Phase.BUILT || phase == Phase.GROWING;
    }

    /** Whether a block is within its walls: over {@link #built}, from layer 0 up {@link #WALLS_HIGH}. */
    public boolean holds(Pos at) {
        return at.x() >= built.minX() && at.x() <= built.maxX() && at.z() >= built.minZ() && at.z() <= built.maxZ()
                && at.y() >= anchor.y() && at.y() <= anchor.y() + WALLS_HIGH;
    }

    /** Whether a door is a house's, shut after passing: within the walls of one that stands. */
    /**
     * Whether a column is on the ground of one of these buildings, planned or standing: its pad and
     * the {@link #FELL_PAST} round it the flatten eases. No body digs in for the night there: a
     * pocket dug in a half-built house cut its floor (2026-10-20).
     */
    public static boolean onAPad(java.util.Collection<Structure> structures, int x, int z) {
        for (Structure structure : structures) {
            Footprint pad = structure.fellingArea();
            if (structure.phase() != Phase.REFUSED && x >= pad.minX() && x <= pad.maxX() && z >= pad.minZ()
                    && z <= pad.maxZ()) {
                return true;
            }
        }
        return false;
    }

    public static boolean houseDoor(java.util.Collection<Structure> structures, Pos door) {
        for (Structure structure : structures) {
            if (structure.stands() && structure.holds(door)) {
                return true;
            }
        }
        return false;
    }

    public Structure at(Phase next, String why) {
        return new Structure(id, blueprint, version, variants, bindings, anchor, placement, built, pad, next, sitedAt,
                why, work, grownFrom);
    }

    public Structure with(Work next) {
        return new Structure(id, blueprint, version, variants, bindings, anchor, placement, built, pad, phase, sitedAt,
                note, next, grownFrom);
    }

    public Structure rebound(Map<Integer, String> next) {
        return new Structure(id, blueprint, version, variants, next, anchor, placement, built, pad, phase, sitedAt,
                note, work, grownFrom);
    }

    /** Growing into {@code next} from the variants it stands with. */
    public Structure growing(Map<String, String> next, String why) {
        return new Structure(id, blueprint, version, next, bindings, anchor, placement, built, pad, Phase.GROWING,
                sitedAt, why, work, variants);
    }

    /** Its growth given up: it stands as it did. */
    public Structure notGrown(String why) {
        return new Structure(id, blueprint, version, grownFrom, bindings, anchor, placement, built, pad, Phase.BUILT,
                sitedAt, why, work, Map.of());
    }

    /** Its growth stands. */
    public Structure grown(String why) {
        return new Structure(id, blueprint, version, variants, bindings, anchor, placement, built, pad, Phase.BUILT,
                sitedAt, why, work, Map.of());
    }

    /** The columns its trees are felled from: the pad and {@link #FELL_PAST} round it. */
    public Footprint fellingArea() {
        return new Footprint(pad.minX() - FELL_PAST, pad.minZ() - FELL_PAST, pad.maxX() + FELL_PAST,
                pad.maxZ() + FELL_PAST);
    }

    /** The columns its plants are pulled up from: the pad and {@link #PLANTS_PAST} round it. */
    public Footprint plantsArea() {
        return new Footprint(pad.minX() - PLANTS_PAST, pad.minZ() - PLANTS_PAST, pad.maxX() + PLANTS_PAST,
                pad.maxZ() + PLANTS_PAST);
    }
}
