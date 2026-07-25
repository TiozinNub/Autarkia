package dev.luizloyola.autarkia.core.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * An immutable value per {@link Knob}, in a flat array indexed by {@code Knob.ordinal()}: this
 * class never names a knob, so a new tunable is one enum constant. Pure core, no I/O — the file is
 * {@code mod/config}'s job, the live configuration {@link Config}'s.
 *
 * <p>Always valid by construction: every entry point runs {@link Knob#clamp}, so a hand-edited file
 * cannot fail to load — {@link #from} returns the nearest legal configuration plus what it had to
 * correct, and a typo warns instead of refusing to boot.
 */
public final class AutarkiaConfig {

    /** Every knob at its {@link Knob#def() documented default} — also what an absent file means. */
    public static final AutarkiaConfig DEFAULTS = new AutarkiaConfig(defaultValues());

    private final double[] values;

    private AutarkiaConfig(double[] values) {
        this.values = values;
    }

    /** The raw stored value. Prefer {@link #i}/{@link #b}/{@link #d} at call sites. */
    public double get(Knob knob) {
        return values[knob.ordinal()];
    }

    public double d(Knob knob) {
        return values[knob.ordinal()];
    }

    /** An {@link Knob.Kind#INT} knob, already rounded by the clamp. */
    public int i(Knob knob) {
        return (int) values[knob.ordinal()];
    }

    public boolean b(Knob knob) {
        return values[knob.ordinal()] != 0.0;
    }

    /** This configuration with one knob changed (clamped), leaving the original untouched. */
    public AutarkiaConfig with(Knob knob, double raw) {
        double[] copy = values.clone();
        copy[knob.ordinal()] = knob.clamp(raw);
        return new AutarkiaConfig(copy);
    }

    /** Every knob and its current value — the encoding side of the JSON round trip. */
    public Map<Knob, Double> toMap() {
        Map<Knob, Double> map = new EnumMap<>(Knob.class);
        for (Knob knob : Knob.values()) {
            map.put(knob, values[knob.ordinal()]);
        }
        return map;
    }

    /** Whether this differs from {@link #DEFAULTS} for the given knob — what {@code show} marks. */
    public boolean isDefault(Knob knob) {
        return values[knob.ordinal()] == knob.def();
    }

    /**
     * Builds a configuration from whatever a file (or a command, or a GUI) supplied. Knobs absent
     * from {@code raw} keep their default; knobs present but out of range are clamped and reported.
     * The returned {@link Loaded#problems()} are operator-facing sentences, empty when the input was
     * already clean.
     */
    public static Loaded from(Map<Knob, Double> raw) {
        double[] built = defaultValues();
        List<String> problems = new ArrayList<>();
        for (Map.Entry<Knob, Double> entry : raw.entrySet()) {
            Knob knob = entry.getKey();
            double supplied = entry.getValue() == null ? knob.def() : entry.getValue();
            double clamped = knob.clamp(supplied);
            built[knob.ordinal()] = clamped;
            if (clamped != supplied) {
                problems.add(String.format(Locale.ROOT, "%s: %s is out of range [%s, %s] — using %s",
                        knob.key(), knob.format(supplied), knob.format(knob.min()),
                        knob.format(knob.max()), knob.format(clamped)));
            }
        }
        return new Loaded(new AutarkiaConfig(built), List.copyOf(problems));
    }

    /** A configuration plus whatever had to be corrected to make it legal. @see #from */
    public record Loaded(AutarkiaConfig config, List<String> problems) {
        public Loaded {
            problems = List.copyOf(problems);
        }

        /** True when the input needed no correction — nothing to warn about. */
        public boolean clean() {
            return problems.isEmpty();
        }
    }

    private static double[] defaultValues() {
        double[] built = new double[Knob.values().length];
        for (Knob knob : Knob.values()) {
            built[knob.ordinal()] = knob.def();
        }
        return built;
    }

    /** Key/value lines for the knobs that differ from the defaults — the compact "what's custom". */
    public List<String> describeOverrides() {
        List<String> lines = new ArrayList<>();
        for (Knob knob : Knob.values()) {
            if (!isDefault(knob)) {
                lines.add(knob.key() + " = " + knob.format(get(knob))
                        + " (default " + knob.format(knob.def()) + ")");
            }
        }
        return Collections.unmodifiableList(lines);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof AutarkiaConfig
                && java.util.Arrays.equals(values, ((AutarkiaConfig) other).values);
    }

    @Override
    public int hashCode() {
        return java.util.Arrays.hashCode(values);
    }

    @Override
    public String toString() {
        List<String> overrides = describeOverrides();
        return overrides.isEmpty() ? "AutarkiaConfig(defaults)" : "AutarkiaConfig" + overrides;
    }
}
