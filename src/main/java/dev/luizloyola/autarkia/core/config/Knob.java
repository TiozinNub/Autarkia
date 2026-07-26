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

    // --- peers (the people sense) -----------------------------------------------------------

    /** @see dev.luizloyola.autarkia.core.brain.sense.PeerSensorCore#radius() */
    PEERS_RADIUS("peers.radius", Kind.INT, 24, 4, 64,
            "How far (blocks) another person can be perceived at all."),
    /** Read by the mod-side candidate query against sneaking targets. */
    PEERS_SNEAK_RANGE_MULT("peers.sneak_range_mult", Kind.DOUBLE, 0.75, 0.1, 1.0,
            "Detection range multiplier against a SNEAKING target — sneaking shrinks how far "
                    + "away you are noticed, it never makes you invisible."),
    /** @see dev.luizloyola.autarkia.core.brain.sense.PeerSensorCore#coneDegrees() */
    PEERS_CONE_DEGREES("peers.cone_degrees", Kind.INT, 200, 30, 360,
            "Horizontal field of view (degrees). People outside it are unseen until they make "
                    + "noise; 360 restores the old omniscience."),
    /** Read by the mod-side ear when deciding which sounds reach her. */
    PEERS_HEARING_RADIUS("peers.hearing_radius", Kind.INT, 12, 0, 32,
            "How far (blocks) sound-makers are noticed regardless of the view cone. Sneaking "
                    + "people are silent (vanilla's own rule); 0 makes her deaf."),
    /** @see dev.luizloyola.autarkia.core.brain.sense.PeerSensorCore#lingerTicks() */
    PEERS_LINGER_TICKS("peers.linger_ticks", Kind.INT, 100, 0, 1200,
            "Object permanence: how long a peer stays perceived (frozen, as remembered) after "
                    + "every channel goes dark — the walking-behind-a-pillar grace."),
    /** @see dev.luizloyola.autarkia.core.brain.sense.PeerSensorCore#nearIntervalTicks() */
    PEERS_NEAR_INTERVAL("peers.near_interval_ticks", Kind.INT, 1, 1, 100,
            "Attention at point-blank: re-check interval (ticks) for a peer right next to her."),
    /** @see dev.luizloyola.autarkia.core.brain.sense.PeerSensorCore#farIntervalTicks() */
    PEERS_FAR_INTERVAL("peers.far_interval_ticks", Kind.INT, 20, 1, 400,
            "Attention at the edge: re-check interval (ticks) for a peer at max range; "
                    + "distances between lerp between the two."),

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
