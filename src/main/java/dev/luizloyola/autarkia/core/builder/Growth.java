package dev.luizloyola.autarkia.core.builder;

import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.autarkia.core.bp.Blueprint.Outcome;
import dev.luizloyola.autarkia.core.bp.BuildPlan;
import dev.luizloyola.autarkia.core.bp.PlanArgs;
import dev.luizloyola.autarkia.core.bp.Placement;
import dev.luizloyola.autarkia.core.bp.Variants;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * A growth a standing building can take for a station it lacks: the same building with one group's
 * variant changed (docs/superpowers/specs/2026-10-02-house-grows-design.md). The basic house's
 * {@code base=lv2} adds two double chests, {@code base=lv3} a furnace as well.
 *
 * @param variants every group pinned, {@code none} for an optional group left out
 */
public record Growth(UUID structure, Map<String, String> variants) {

    public Growth {
        variants = Map.copyOf(variants);
    }

    /**
     * What a selection's plan holds.
     *
     * @param stations how many cells of each station's block, by block id
     * @param blocks   how many cells place a block: the size of the house
     */
    public record Holds(Map<String, Integer> stations, int blocks) {
        public Holds {
            stations = Map.copyOf(stations);
        }

        int of(String block) {
            return stations.getOrDefault(block, 0);
        }
    }

    /**
     * The least growth from {@code current} that holds more of {@code station} and no fewer of any
     * station it holds now: one group changed, the fewest blocks, the file's order among ties. Empty
     * when no such selection is allowed.
     *
     * @param holds what a selection's plan holds; empty when it cannot be planned
     */
    public static Optional<Map<String, String>> choose(Variants variants, Map<String, String> current, String station,
                                                       Function<Map<String, String>, Optional<Holds>> holds) {
        Map<String, String> now = pinned(variants, current);
        Optional<Holds> was = holds.apply(now);
        if (was.isEmpty()) {
            return Optional.empty();
        }
        Map<String, String> best = null;
        int bestBlocks = Integer.MAX_VALUE;
        for (Map<String, String> candidate : neighbours(variants, now)) {
            Optional<Holds> would = holds.apply(candidate);
            if (would.isEmpty() || would.get().of(station) <= was.get().of(station)
                    || was.get().stations().entrySet().stream().anyMatch(e -> would.get().of(e.getKey()) < e.getValue())) {
                continue;
            }
            if (would.get().blocks() < bestBlocks) {
                best = candidate;
                bestBlocks = would.get().blocks();
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * The cells a growth changes, in the world: where {@code to} places a block {@code from} does
     * not, or clears one it placed. The rest of the building is never touched. Both plans share the
     * box every variant's grid lies in, so the anchor never moves.
     *
     * @return the block {@code to} wants there, null for air
     */
    public static Map<Pos, Outcome> changed(BuildPlan from, BuildPlan to, Placement placement, Pos anchor) {
        Map<Pos, String> was = new HashMap<>();
        from.forEach(placement, (dx, layer, dz, kind, state) -> {
            if (kind == BuildPlan.CellKind.BLOCK && state != null) {
                was.put(new Pos(anchor.x() + dx, anchor.y() + layer, anchor.z() + dz), state.block());
            }
        });
        Map<Pos, Outcome> changed = new LinkedHashMap<>();
        to.forEach(placement, (dx, layer, dz, kind, state) -> {
            Pos at = new Pos(anchor.x() + dx, anchor.y() + layer, anchor.z() + dz);
            String before = was.get(at);
            if (kind == BuildPlan.CellKind.BLOCK && state != null && !state.block().equals(before)) {
                changed.put(at, state);
            } else if (kind == BuildPlan.CellKind.AIR && before != null) {
                changed.put(at, null);
            }
        });
        return changed;
    }

    /** Every group named, an optional one left out named {@code none}. */
    static Map<String, String> pinned(Variants variants, Map<String, String> chosen) {
        Map<String, String> pinned = new LinkedHashMap<>();
        for (Variants.Group group : variants.groups()) {
            String variant = chosen.get(group.name());
            pinned.put(group.name(), variant == null ? PlanArgs.NONE_VARIANT : variant);
        }
        return pinned;
    }

    /** The allowed selections one group away from {@code now}, in the file's order. */
    private static List<Map<String, String>> neighbours(Variants variants, Map<String, String> now) {
        List<Map<String, String>> out = new ArrayList<>();
        for (Variants.Group group : variants.groups()) {
            List<String> options = new ArrayList<>(group.variants());
            if (!group.required()) {
                options.add(PlanArgs.NONE_VARIANT);
            }
            for (String option : options) {
                if (option.equals(now.get(group.name()))) {
                    continue;
                }
                Map<String, String> candidate = new LinkedHashMap<>(now);
                candidate.put(group.name(), option);
                if (allowed(variants, candidate)) {
                    out.add(candidate);
                }
            }
        }
        return out;
    }

    private static boolean allowed(Variants variants, Map<String, String> pinned) {
        Map<String, String> chosen = new LinkedHashMap<>(pinned);
        chosen.values().removeIf(PlanArgs.NONE_VARIANT::equals);
        return variants.selections().stream().anyMatch(selection -> selection.chosen().equals(chosen));
    }
}
