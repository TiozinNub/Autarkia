package dev.luizloyola.autarkia.core.config;

import java.util.Locale;
import java.util.Optional;

/**
 * The single source of truth for every tunable Autarkia exposes: one constant per knob — dotted
 * key, type, default, legal range. The JSON schema, the {@code /autarkia config} completions, the
 * validation clamp and the optional YACL screen all derive from this list, and nothing reflects
 * over field names, so a new tunable is a line here plus a one-line accessor.
 *
 * <p>Keys are dotted {@code snake_case}, the leading segment naming the JSON object the knob nests
 * under — the convention Minecraft itself moved to in 26.1.
 *
 * <p>Ranges are safety bounds, not taste: they only stop a hand-edited file producing a Person that
 * cannot function or a server that stalls. {@link AutarkiaConfig} clamps rather than rejects, so
 * one bad line warns instead of failing the whole file.
 */
public enum Knob {

    // --- arbitration ------------------------------------------------------------------------

    /** @see dev.luizloyola.autarkia.core.brain.Arbiter#stickiness() */
    BRAIN_STICKINESS("brain.stickiness", Kind.DOUBLE, 0.1, 0.0, 1.0,
            "Incumbency bonus added to the active instinct's bid — the anti-dithering hysteresis."),
    /** @see dev.luizloyola.autarkia.core.brain.Arbiter#preempt() */
    BRAIN_PREEMPT("brain.preempt", Kind.DOUBLE, 0.6, 0.0, 1.0,
            "Minimum raw pressure a challenger needs to cut into a running task mid-flight."),

    // --- instincts --------------------------------------------------------------------------

    /** @see dev.luizloyola.autarkia.core.brain.instinct.FleeInstinct#range() */
    FLEE_RANGE("instincts.flee_range", Kind.DOUBLE, 16.0, 1.0, 64.0,
            "Distance (blocks) at which a threat starts to register at all."),
    /** @see dev.luizloyola.autarkia.core.brain.instinct.FleeInstinct#ramp() */
    FLEE_RAMP("instincts.flee_ramp", Kind.DOUBLE, 12.0, 1.0, 64.0,
            "Distance (blocks) over which flee pressure ramps from nothing to full panic."),
    /** @see dev.luizloyola.autarkia.core.brain.instinct.FleeInstinct#rangedRangeMult() */
    FLEE_RANGED_RANGE_MULT("instincts.flee_ranged_range_mult", Kind.DOUBLE, 1.5, 1.0, 4.0,
            "Flee-range multiplier against a RANGED source (a skeleton's bow, a drowned's "
                    + "trident, anyone aiming) — arrows out-reach claws, so fear starts farther."),
    /** @see dev.luizloyola.autarkia.core.brain.instinct.FleeInstinct#approachBonus() */
    FLEE_APPROACH_BONUS("instincts.flee_approach_bonus", Kind.DOUBLE, 1.3, 1.0, 4.0,
            "Pressure multiplier when the threat is measurably CLOSING IN — the observable "
                    + "stand-in for 'it is hunting me'."),
    /** @see dev.luizloyola.autarkia.core.brain.instinct.DescendInstinct#pressure() */
    DESCEND_PRESSURE("instincts.descend_pressure", Kind.DOUBLE, 0.45, 0.0, 1.0,
            "Pressure to climb down off an orphaned pillar. Keep below brain.preempt so a "
                    + "legitimate mid-climb chop is never interrupted."),
    /** @see dev.luizloyola.autarkia.core.brain.instinct.WanderInstinct#idlePressure() */
    WANDER_IDLE_PRESSURE("instincts.wander_idle_pressure", Kind.DOUBLE, 0.15, 0.0, 1.0,
            "The do-something floor. Every real drive must beat this; at 0 an unbothered "
                    + "Person stands still."),
    /** @see dev.luizloyola.autarkia.core.brain.instinct.WanderInstinct#defaultRadius() */
    WANDER_RADIUS("instincts.wander_radius", Kind.INT, 8, 1, 64,
            "How far (blocks) an idle saunter may roll its next beat."),

    // --- perception -------------------------------------------------------------------------

    /** @see dev.luizloyola.autarkia.core.brain.knowledge.CrescentSampler#radius() */
    SENSE_RADIUS("perception.sense_radius", Kind.INT, 12, 1, 32,
            "Horizontal sense radius (blocks). Cost scales with the square — a walking Person "
                    + "sweeps about 2R columns per block moved."),
    /** @see dev.luizloyola.autarkia.core.brain.knowledge.PoiSensorCore#readsPerTick() */
    READS_PER_TICK("perception.reads_per_tick", Kind.INT, 64, 1, 4096,
            "Block-read budget per Person per tick. The main throughput/TPS dial."),
    /** @see dev.luizloyola.autarkia.core.brain.knowledge.PoiSensorCore#queueCap() */
    QUEUE_CAP("perception.queue_cap", Kind.INT, 512, 16, 65_536,
            "How many un-probed columns may back up before new sightings are dropped."),
    /** @see dev.luizloyola.autarkia.core.brain.knowledge.RegionGrowth#maxBlocks() */
    REGION_MAX_BLOCKS("perception.region_max_blocks", Kind.INT, 512, 16, 16_384,
            "Block cap on one structure scan; hitting it marks the region partial."),
    /** @see dev.luizloyola.autarkia.core.brain.knowledge.RegionGrowth#maxSpread() */
    REGION_MAX_SPREAD("perception.region_max_spread", Kind.INT, 24, 1, 128,
            "Chebyshev spread cap from the seed — what splits a fused mega-forest into groves."),

    KNOWLEDGE_MAX_PER_KIND("perception.knowledge_max_per_kind", Kind.INT, 160, 8, 1024,
            "POI memories kept per kind before the stalest is evicted. Must exceed the trees"
                    + " a person works among, or edge trees churn forget/rediscover forever"
                    + " (an 81-tree grid starved the far corners at 64)."),

    // --- peers (the people sense) -----------------------------------------------------------

    /** @see dev.luizloyola.autarkia.core.brain.sense.BeingSensorCore#radius() */
    PEERS_RADIUS("peers.radius", Kind.INT, 24, 4, 64,
            "How far (blocks) another person can be perceived at all."),
    /** Read by the mod-side candidate query against sneaking targets. */
    PEERS_SNEAK_RANGE_MULT("peers.sneak_range_mult", Kind.DOUBLE, 0.75, 0.1, 1.0,
            "Detection range multiplier against a SNEAKING target — sneaking shrinks how far "
                    + "away you are noticed, it never makes you invisible."),
    /** @see dev.luizloyola.autarkia.core.brain.sense.BeingSensorCore#coneDegrees() */
    PEERS_CONE_DEGREES("peers.cone_degrees", Kind.INT, 150, 30, 360,
            "Horizontal field of view (degrees). People outside it are unseen until they make "
                    + "noise; 360 restores the old omniscience."),
    /** @see dev.luizloyola.autarkia.core.brain.sense.BeingSensorCore#verticalHalfDegrees() */
    PEERS_VERTICAL_DEGREES("peers.vertical_degrees", Kind.INT, 60, 5, 90,
            "Vertical field HALF-angle (degrees) around gaze pitch — human vision is wide "
                    + "across but flat; 90 removes the up/down limit."),
    /** Read by the mod-side ear when deciding which sounds reach a listener. */
    PEERS_HEARING_RADIUS("peers.hearing_radius", Kind.INT, 12, 0, 32,
            "How far (blocks) sound-makers are noticed regardless of the view cone. Sneaking "
                    + "people are silent (vanilla's own rule); 0 makes a person deaf."),
    /** @see dev.luizloyola.autarkia.core.brain.sense.BeingSensorCore#lingerTicks() */
    PEERS_LINGER_TICKS("peers.linger_ticks", Kind.INT, 300, 0, 2400,
            "Object permanence: how long a tracked being stays perceived (frozen, as "
                    + "remembered) after every channel goes dark. 15s bridges 2+ idle calls, "
                    + "so repeated moos keep tracking the same unseen mob (decision: Luiz)."),
    /** @see dev.luizloyola.autarkia.core.brain.sense.BeingSensorCore#heardActivityDecayTicks() */
    PEERS_HEARD_DECAY_TICKS("peers.heard_activity_decay_ticks", Kind.INT, 60, 0, 1200,
            "How long a sound-told activity (heard mining, a heard scuffle) stays believed "
                    + "after the sound stops — then only the bare presence is left."),
    /** @see dev.luizloyola.autarkia.core.brain.sense.BeingSensorCore#nearIntervalTicks() */
    PEERS_NEAR_INTERVAL("peers.near_interval_ticks", Kind.INT, 1, 1, 100,
            "Attention at point-blank: re-check interval (ticks) for a body standing right there."),
    /** @see dev.luizloyola.autarkia.core.brain.sense.BeingSensorCore#farIntervalTicks() */
    PEERS_FAR_INTERVAL("peers.far_interval_ticks", Kind.INT, 20, 1, 400,
            "Attention at the edge: re-check interval (ticks) for a body at max range; "
                    + "distances between lerp between the two."),
    /** @see dev.luizloyola.autarkia.core.brain.sense.BeingSensorCore#herdLinkRadius() */
    PEERS_HERD_LINK_RADIUS("peers.herd_link_radius", Kind.INT, 12, 2, 24,
            "How far apart (blocks, per axis) two same-species animals may stand and still "
                    + "chain into one herd — herds are chains, so a spread pasture links up."),
    /** @see dev.luizloyola.autarkia.core.brain.sense.BeingSensorCore#rayBudgetBase() */
    PEERS_RAY_BUDGET("peers.ray_budget", Kind.INT, 8, 1, 256,
            "Base line-of-sight checks per Person per tick. The effective budget scales up "
                    + "with the backlog (max of this and a quarter of the due work), so a "
                    + "100-mob wave is noticed within ~4 ticks — deferred, never skipped."),

    // --- danger (the flee weighting; per-species weights live in the danger file section) ----

    /** @see dev.luizloyola.autarkia.core.brain.instinct.Danger#meleeMult() */
    DANGER_MELEE_MULT("danger.melee_mult", Kind.DOUBLE, 1.15, 0.0, 4.0,
            "Danger multiplier for a visibly held melee weapon — a zombie with a sword "
                    + "outranks bare claws (decision: Luiz)."),
    /** @see dev.luizloyola.autarkia.core.brain.instinct.Danger#rangedMult() */
    DANGER_RANGED_MULT("danger.ranged_mult", Kind.DOUBLE, 1.25, 0.0, 4.0,
            "Danger multiplier for a visibly held ranged weapon (bow, crossbow, trident)."),
    /** @see dev.luizloyola.autarkia.core.brain.instinct.Danger#armoredMult() */
    DANGER_ARMORED_MULT("danger.armored_mult", Kind.DOUBLE, 1.2, 0.0, 4.0,
            "Danger multiplier for visible armor on the body."),
    /** @see dev.luizloyola.autarkia.core.brain.instinct.Danger#mountedMult() */
    DANGER_MOUNTED_MULT("danger.mounted_mult", Kind.DOUBLE, 1.15, 0.0, 4.0,
            "Danger multiplier for a mounted body (spider jockeys, skeleton horsemen)."),
    /** @see dev.luizloyola.autarkia.core.brain.instinct.Danger#babyMult() */
    DANGER_BABY_MULT("danger.baby_mult", Kind.DOUBLE, 1.2, 0.0, 4.0,
            "Danger multiplier for a baby variant — smaller, faster, harder to hit."),

    // --- social (talking, hailing, grouping up) ----------------------------------------------

    /** Read by the mod-side ear, which sizes itself to the LOUDEST social range. */
    SOCIAL_HAIL_RADIUS("social.hail_radius", Kind.INT, 48, 8, 64,
            "How far (blocks) a HAIL carries — the deliberate shout that says 'hey, over "
                    + "there'. It must outrange sight (peers.radius) or it would add nothing to "
                    + "simply noticing someone. This is also the ear's own listener radius; "
                    + "quieter sounds are narrowed back down to peers.hearing_radius."),

    // --- claims -----------------------------------------------------------------------------

    /** @see dev.luizloyola.autarkia.core.brain.board.SiteClaims#ttlTicks() */
    CLAIM_TTL_TICKS("claims.ttl_ticks", Kind.INT, 600, 20, 72_000,
            "How long a site claim outlives its last heartbeat before another Person may take "
                    + "the spot (20 ticks = 1 second)."),

    // --- journal ----------------------------------------------------------------------------

    /** @see dev.luizloyola.autarkia.core.log.JournalService#defaultMaxEntriesPerPerson() */
    JOURNAL_MAX_ENTRIES("journal.max_entries_per_person", Kind.INT, 256, 16, 8192,
            "Ring size per Person. Older entries are evicted once it fills."),
    /** @see dev.luizloyola.autarkia.core.log.JournalService#defaultMaxAgeTicks() */
    JOURNAL_MAX_AGE_TICKS("journal.max_age_ticks", Kind.INT, 12_000, 20, 1_728_000,
            "Age cutoff for journal entries (default 10 minutes of game time)."),
    /** Read by the mod-side journal store's periodic sweep. */
    JOURNAL_SWEEP_INTERVAL("journal.sweep_interval_ticks", Kind.INT, 600, 20, 72_000,
            "How often the journal store evicts aged-out entries."),
    /** Read by the mod-side journal file sink when a world loads. */
    JOURNAL_FILE_SINK("journal.file_sink", Kind.BOOL, 0, 0, 1,
            "Mirror each Person's journal to logs/autarkia/<person>.log on disk.");

    /** What a knob holds. Everything is stored as a double; the kind decides how it reads back. */
    public enum Kind { DOUBLE, INT, BOOL }

    private final String key;
    private final Kind kind;
    private final double def;
    private final double min;
    private final double max;
    private final String doc;

    Knob(String key, Kind kind, double def, double min, double max, String doc) {
        this.key = key;
        this.kind = kind;
        this.def = def;
        this.min = min;
        this.max = max;
        this.doc = doc;
    }

    /** The dotted, snake_case key — the name used in JSON, in commands, and in log messages. */
    public String key() {
        return key;
    }

    /** The JSON object this knob nests under ({@code "perception"} for {@code perception.*}). */
    public String section() {
        return key.substring(0, key.indexOf('.'));
    }

    public String leaf() {
        return key.substring(key.indexOf('.') + 1);
    }

    public Kind kind() {
        return kind;
    }

    public double def() {
        return def;
    }

    public double min() {
        return min;
    }

    public double max() {
        return max;
    }

    /** One sentence for the operator — shown by {@code /autarkia config show} and in the GUI. */
    public String doc() {
        return doc;
    }

    /** What this knob will accept, phrased for an error message ("a whole number"). */
    public String expects() {
        switch (kind) {
            case BOOL:
                return "true or false";
            case INT:
                return "a whole number in [" + format(min) + ", " + format(max) + "]";
            case DOUBLE:
            default:
                return "a number in [" + format(min) + ", " + format(max) + "]";
        }
    }

    /** Constrains {@code raw} to this knob's range, rounding to whole numbers for INT and BOOL. */
    public double clamp(double raw) {
        double bounded = Math.min(max, Math.max(min, raw));
        return kind == Kind.DOUBLE ? bounded : Math.rint(bounded);
    }

    /** Whether {@code raw} would survive {@link #clamp} untouched — the "is this file sane" check. */
    public boolean accepts(double raw) {
        return Double.isFinite(raw) && clamp(raw) == raw;
    }

    /** Renders a stored value the way it should appear in JSON and in command output. */
    public String format(double value) {
        switch (kind) {
            case BOOL:
                return value != 0.0 ? "true" : "false";
            case INT:
                return Long.toString((long) value);
            case DOUBLE:
            default:
                return String.format(Locale.ROOT, "%s", value);
        }
    }

    /**
     * Reads an operator-typed value ({@code "12"}, {@code "0.45"}, {@code "true"}) for this knob,
     * or empty when it isn't a value of this knob's kind. Out-of-range but well-formed input parses
     * fine — clamping is {@link AutarkiaConfig}'s job, so the caller can report what it did.
     */
    public Optional<Double> parse(String text) {
        String trimmed = text.trim();
        if (kind == Kind.BOOL) {
            if (trimmed.equalsIgnoreCase("true")) {
                return Optional.of(1.0);
            }
            if (trimmed.equalsIgnoreCase("false")) {
                return Optional.of(0.0);
            }
            return Optional.empty();
        }
        try {
            double parsed = Double.parseDouble(trimmed);
            if (!Double.isFinite(parsed)) {
                return Optional.empty();
            }
            return kind == Kind.INT && parsed != Math.rint(parsed)
                    ? Optional.empty()
                    : Optional.of(parsed);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** The knob with this dotted key, or empty — the lookup behind {@code config get}/{@code set}. */
    public static Optional<Knob> byKey(String key) {
        for (Knob knob : values()) {
            if (knob.key.equals(key)) {
                return Optional.of(knob);
            }
        }
        return Optional.empty();
    }
}
