package dev.luizloyola.autarkia.core.bp;

import dev.luizloyola.autarkia.core.bp.Blueprint.Facing;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * The words after a blueprint's id in {@code bp place} and {@code bp bill}, in any order: pins
 * ({@code 1=spruce}, commas or spaces between), variant pins ({@code beds=three},
 * {@code cellar=none}), a facing for the drawing's north edge, {@code flip}, and {@code slow} or
 * {@code slow=<ticks>} to place block by block in the builder's order.
 *
 * @param variants group to variant, or to {@code none} for an optional group left out
 * @param slow     ticks between blocks, 0 to place at once
 */
public record PlanArgs(Map<Integer, String> pins, Map<String, String> variants, @Nullable Facing facing,
                       boolean flip, int slow) {

    public static final PlanArgs NONE = new PlanArgs(Map.of(), Map.of(), null, false, 0);

    /** Five blocks a second: slow enough to follow, quick enough to watch a house go up. */
    public static final int SLOW_TICKS = 4;
    private static final int SLOWEST = 200;

    /** What a pin says to leave an optional group out. */
    public static final String NONE_VARIANT = "none";

    public PlanArgs {
        pins = Collections.unmodifiableMap(new LinkedHashMap<>(pins));
        variants = Collections.unmodifiableMap(new LinkedHashMap<>(variants));
    }

    public static PlanArgs parse(String words, Diagnostics out) {
        Map<Integer, String> pins = new LinkedHashMap<>();
        Map<String, String> variants = new LinkedHashMap<>();
        Facing facing = null;
        boolean flip = false;
        int slow = 0;
        for (String word : words.strip().split("[\\s,]+")) {
            if (word.isEmpty()) {
                continue;
            }
            Facing named = Facing.of(word);
            int equals = word.indexOf('=');
            if (named != null) {
                if (facing != null && facing != named) {
                    out.error("facing_twice", 0, 0, "the north edge faces " + facing.word() + " or " + word
                            + ", not both");
                }
                facing = named;
            } else if (word.equals("flip")) {
                flip = true;
            } else if (word.equals("slow")) {
                slow = SLOW_TICKS;
            } else if (word.startsWith("slow=")) {
                String ticks = word.substring("slow=".length());
                slow = ticks.matches("[0-9]{1,3}") ? Integer.parseInt(ticks) : 0;
                if (slow < 1 || slow > SLOWEST) {
                    out.error("slow_ticks", 0, 0, "slow= takes the ticks between blocks, 1 to " + SLOWEST + ", not '"
                            + ticks + "'");
                }
            } else if (equals > 0 && word.substring(0, equals).matches("[0-9]+") && equals < word.length() - 1) {
                int slot = Integer.parseInt(word.substring(0, equals));
                String value = word.substring(equals + 1);
                String was = pins.putIfAbsent(slot, value);
                if (was != null && !was.equals(value)) {
                    out.error("pin_twice", 0, 0, "slot " + slot + " is pinned to " + was + " and to " + value);
                }
            } else if (equals > 0 && word.matches("[a-z][a-z0-9_]*=[a-z0-9_]+")) {
                String group = word.substring(0, equals);
                String value = word.substring(equals + 1);
                String was = variants.putIfAbsent(group, value);
                if (was != null && !was.equals(value)) {
                    out.error("pin_twice", 0, 0, "group " + group + " is pinned to " + was + " and to " + value);
                }
            } else {
                out.error("arg_unknown", 0, 0, "'" + word + "' is not a pin like 1=spruce or beds=two, a facing "
                        + "(north, east, south, west), flip or slow");
            }
        }
        return new PlanArgs(pins, variants, facing, flip, slow);
    }

    /**
     * The placement these words ask of {@code bp}: the facing given, else as drawn when the
     * blueprint allows it, else the first it allows. Refused when the blueprint's headers forbid it.
     */
    public @Nullable Placement placement(Blueprint bp, Diagnostics out) {
        Set<Facing> allowed = bp.headers().orientation();
        Facing north = facing;
        if (north == null) {
            north = allowed.contains(Facing.NORTH) ? Facing.NORTH
                    : Arrays.stream(Facing.values()).filter(allowed::contains).findFirst().orElse(Facing.NORTH);
        }
        boolean ok = true;
        if (!allowed.contains(north)) {
            out.error("facing_refused", 0, 0, "this blueprint's north edge may face " + words(allowed) + ", not "
                    + north.word());
            ok = false;
        }
        if (flip && !bp.headers().flippable()) {
            out.error("flip_refused", 0, 0, "this blueprint says flippable false");
            ok = false;
        }
        return ok ? new Placement(north, flip) : null;
    }

    private static String words(Set<Facing> facings) {
        return EnumSet.copyOf(facings).stream().map(Facing::word).collect(Collectors.joining(", "));
    }
}
