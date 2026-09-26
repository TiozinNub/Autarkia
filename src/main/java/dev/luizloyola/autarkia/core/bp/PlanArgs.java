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
 * ({@code 1=spruce}, commas or spaces between), a facing for the drawing's north edge, and
 * {@code flip}.
 */
public record PlanArgs(Map<Integer, String> pins, @Nullable Facing facing, boolean flip) {

    public static final PlanArgs NONE = new PlanArgs(Map.of(), null, false);

    public PlanArgs {
        pins = Collections.unmodifiableMap(new LinkedHashMap<>(pins));
    }

    public static PlanArgs parse(String words, Diagnostics out) {
        Map<Integer, String> pins = new LinkedHashMap<>();
        Facing facing = null;
        boolean flip = false;
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
            } else if (equals > 0 && word.substring(0, equals).matches("[0-9]+") && equals < word.length() - 1) {
                int slot = Integer.parseInt(word.substring(0, equals));
                String value = word.substring(equals + 1);
                String was = pins.putIfAbsent(slot, value);
                if (was != null && !was.equals(value)) {
                    out.error("pin_twice", 0, 0, "slot " + slot + " is pinned to " + was + " and to " + value);
                }
            } else {
                out.error("arg_unknown", 0, 0, "'" + word + "' is not a pin like 1=spruce, a facing (north, east, "
                        + "south, west) or flip");
            }
        }
        return new PlanArgs(pins, facing, flip);
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
