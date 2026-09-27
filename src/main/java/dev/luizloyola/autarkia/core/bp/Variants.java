package dev.luizloyola.autarkia.core.bp;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.BiConsumer;

/**
 * A blueprint's groups of interchangeable variants and the selections they allow (variants spec,
 * 2026-09-27). A variant is an overlay on the base: the cells it changes, and nothing where it
 * leaves the base as drawn.
 */
public final class Variants {

    public static final Variants NONE = new Variants(List.of(), Map.of(), Map.of(), List.of(Selection.BASE));

    /** An overlay cell the variant leaves as the base has it — {@code ?} in its grid. */
    static final char UNCHANGED = 0;

    /** @param variants in the order the file lists them */
    public record Group(String name, boolean required, List<String> variants, int line) {
        public Group {
            variants = List.copyOf(variants);
        }
    }

    /** At most one variant per group, by group name; a group left out is none. */
    public record Selection(Map<String, String> chosen) {

        public static final Selection BASE = new Selection(Map.of());

        public Selection {
            chosen = Collections.unmodifiableMap(new LinkedHashMap<>(chosen));
        }

        /** {@code group.variant} for each chosen variant. */
        public List<String> keys() {
            return chosen.entrySet().stream().map(e -> e.getKey() + "." + e.getValue()).toList();
        }

        public String describe() {
            return chosen.isEmpty() ? "no variants" : String.join(", ", keys());
        }
    }

    /**
     * One variant's changes over the whole box, and the source position of each.
     *
     * @param layers the layers its grids draw
     */
    record Overlay(String key, char[][][] cells, int[][][] line, int[][][] column, SortedSet<Integer> layers) {
    }

    /** Cells and where each was drawn, by {@code [layer - min][z][x]}. */
    record Composed(char[][][] cells, int[][][] line, int[][][] column) {
    }

    private final List<Group> groups;
    private final Map<String, Set<String>> needs;
    private final Map<String, Overlay> overlays;
    private final List<Selection> selections;

    Variants(List<Group> groups, Map<String, Set<String>> needs, Map<String, Overlay> overlays,
             List<Selection> selections) {
        this.groups = List.copyOf(groups);
        Map<String, Set<String>> copy = new LinkedHashMap<>();
        needs.forEach((key, targets) -> copy.put(key, Collections.unmodifiableSet(new LinkedHashSet<>(targets))));
        this.needs = Collections.unmodifiableMap(copy);
        this.overlays = Collections.unmodifiableMap(new LinkedHashMap<>(overlays));
        this.selections = List.copyOf(selections);
    }

    public boolean isEmpty() {
        return groups.isEmpty();
    }

    public List<Group> groups() {
        return groups;
    }

    /** Every variant as {@code group.variant}, in the order the groups list them. */
    public List<String> keys() {
        List<String> keys = new ArrayList<>();
        groups.forEach(group -> group.variants().forEach(variant -> keys.add(group.name() + "." + variant)));
        return keys;
    }

    /** A variant and everything it needs, directly or through others: the least it is chosen with. */
    public Selection with(String key) {
        Map<String, String> chosen = new LinkedHashMap<>();
        Deque<String> todo = new ArrayDeque<>(List.of(key));
        while (!todo.isEmpty()) {
            String next = todo.poll();
            String group = next.substring(0, next.indexOf('.'));
            if (chosen.putIfAbsent(group, next.substring(group.length() + 1)) == null) {
                todo.addAll(needs.getOrDefault(next, Set.of()));
            }
        }
        return canonical(new Selection(chosen));
    }

    /** What each variant needs, directly, by {@code group.variant}. */
    public Map<String, Set<String>> needs() {
        return needs;
    }

    /** Every selection the file allows, in a fixed order; just {@link Selection#BASE} with no groups. */
    public List<Selection> selections() {
        return selections;
    }

    Map<String, Overlay> overlays() {
        return overlays;
    }

    /** The same selection in the order the groups are declared, whatever order it was built in. */
    Selection canonical(Selection selection) {
        Map<String, String> ordered = new LinkedHashMap<>();
        for (Group group : groups) {
            String variant = selection.chosen().get(group.name());
            if (variant != null) {
                ordered.put(group.name(), variant);
            }
        }
        return new Selection(ordered);
    }

    /** Whether {@code from} needs {@code to}, directly or through others. */
    boolean reaches(String from, String to) {
        return reaches(needs, from, to);
    }

    static boolean reaches(Map<String, Set<String>> needs, String from, String to) {
        Set<String> seen = new HashSet<>();
        Deque<String> todo = new ArrayDeque<>(needs.getOrDefault(from, Set.of()));
        while (!todo.isEmpty()) {
            String next = todo.poll();
            if (next.equals(to)) {
                return true;
            }
            if (seen.add(next)) {
                todo.addAll(needs.getOrDefault(next, Set.of()));
            }
        }
        return false;
    }

    /**
     * The chosen overlays, each after every overlay it needs and otherwise in group order, so a
     * variant that needs another may change what that one drew.
     */
    List<Overlay> ordered(Selection selection) {
        List<String> pending = new ArrayList<>(selection.keys());
        List<Overlay> ordered = new ArrayList<>();
        while (!pending.isEmpty()) {
            String next = pending.stream().filter(key -> pending.stream()
                    .noneMatch(other -> !other.equals(key) && reaches(key, other))).findFirst()
                    .orElse(pending.get(0));
            pending.remove(next);
            Overlay overlay = overlays.get(next);
            if (overlay != null) {
                ordered.add(overlay);
            }
        }
        return ordered;
    }

    /** The base with the overlays laid over it in order; the arrays given are never changed. */
    static Composed compose(char[][][] cells, int[][][] line, int[][][] column, List<Overlay> overlays) {
        char[][][] outCells = new char[cells.length][][];
        int[][][] outLine = new int[cells.length][][];
        int[][][] outColumn = new int[cells.length][][];
        for (int l = 0; l < cells.length; l++) {
            outCells[l] = new char[cells[l].length][];
            outLine[l] = new int[cells[l].length][];
            outColumn[l] = new int[cells[l].length][];
            for (int z = 0; z < cells[l].length; z++) {
                outCells[l][z] = cells[l][z].clone();
                outLine[l][z] = line[l][z].clone();
                outColumn[l][z] = column[l][z].clone();
            }
        }
        for (Overlay overlay : overlays) {
            for (int l = 0; l < cells.length; l++) {
                for (int z = 0; z < cells[l].length; z++) {
                    for (int x = 0; x < cells[l][z].length; x++) {
                        if (overlay.cells()[l][z][x] != UNCHANGED) {
                            outCells[l][z][x] = overlay.cells()[l][z][x];
                            outLine[l][z][x] = overlay.line()[l][z][x];
                            outColumn[l][z][x] = overlay.column()[l][z][x];
                        }
                    }
                }
            }
        }
        return new Composed(outCells, outLine, outColumn);
    }

    /**
     * Every selection of these groups that keeps every need, in order — or empty when there are
     * more than {@code limit} to try, before any is built.
     */
    static Optional<List<Selection>> enumerate(List<Group> groups, Map<String, Set<String>> needs, int limit) {
        long product = 1;
        for (Group group : groups) {
            product *= group.variants().size() + (group.required() ? 0 : 1);
            if (product > limit) {
                return Optional.empty();
            }
        }
        List<Selection> all = new ArrayList<>();
        all.add(Selection.BASE);
        for (Group group : groups) {
            List<Selection> next = new ArrayList<>();
            for (Selection partial : all) {
                if (!group.required()) {
                    next.add(partial);
                }
                for (String variant : group.variants()) {
                    Map<String, String> chosen = new LinkedHashMap<>(partial.chosen());
                    chosen.put(group.name(), variant);
                    next.add(new Selection(chosen));
                }
            }
            all = next;
        }
        List<Selection> allowed = new ArrayList<>();
        for (Selection selection : all) {
            Set<String> keys = new HashSet<>(selection.keys());
            boolean kept = keys.stream().allMatch(key -> keys.containsAll(needs.getOrDefault(key, Set.of())));
            if (kept) {
                allowed.add(selection);
            }
        }
        return Optional.of(allowed);
    }

    /**
     * Runs a check once per selection and reports each finding once: as it is when every selection
     * has it, and naming a selection that has it when only some do.
     */
    static void acrossSelections(List<Selection> selections, BiConsumer<Selection, Diagnostics> check,
                                 Diagnostics out) {
        Map<Diagnostic, List<Selection>> found = new LinkedHashMap<>();
        for (Selection selection : selections) {
            Diagnostics each = new Diagnostics();
            check.accept(selection, each);
            for (Diagnostic diagnostic : each.list()) {
                found.computeIfAbsent(diagnostic, d -> new ArrayList<>()).add(selection);
            }
        }
        found.forEach((diagnostic, where) -> {
            if (where.size() == selections.size()) {
                out.add(diagnostic);
                return;
            }
            int others = where.size() - 1;
            out.add(new Diagnostic(diagnostic.severity(), diagnostic.code(), diagnostic.message() + " — with "
                    + where.get(0).describe() + (others == 0 ? "" : " and " + others + " other selection"
                    + (others == 1 ? "" : "s")), diagnostic.line(), diagnostic.column(), diagnostic.cell()));
        });
    }

    static SortedSet<Integer> sorted(Set<Integer> layers) {
        return Collections.unmodifiableSortedSet(new TreeSet<>(layers));
    }
}
