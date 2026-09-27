package dev.luizloyola.autarkia.core.bp;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * A {@code .bp} file as written: sections, tokens and where each sits. Purely syntactic — nothing
 * here knows what {@code oak} is; that is {@link Binder}'s.
 *
 * @param format      the {@code bp <n>} line, 0 when it was missing
 * @param brokenSlots  numbers whose line was already reported, so a use of one is not reported again
 * @param brokenGlyphs legend keys whose line was already reported, likewise
 */
public record BpSource(int format, List<Header> headers, List<Slot> materials, List<Slot> palette,
                       List<Entry> legend, List<GroupDecl> groups, List<NeedDecl> needs, List<Grid> grids,
                       List<NodeDecl> nodes, List<PathChain> paths, Set<Integer> brokenSlots,
                       Set<Character> brokenGlyphs) {

    public BpSource {
        headers = List.copyOf(headers);
        materials = List.copyOf(materials);
        palette = List.copyOf(palette);
        legend = List.copyOf(legend);
        groups = List.copyOf(groups);
        needs = List.copyOf(needs);
        grids = List.copyOf(grids);
        nodes = List.copyOf(nodes);
        paths = List.copyOf(paths);
        brokenSlots = Set.copyOf(brokenSlots);
        brokenGlyphs = Set.copyOf(brokenGlyphs);
    }

    public record Header(String key, String value, int line, int column) {
    }

    /** A {@code materials} or {@code palette} line: its number, its binding, its {@code |}-union. */
    public record Slot(int number, Binding binding, List<Term> terms, int line, int column) {
        public Slot {
            terms = List.copyOf(terms);
        }
    }

    /**
     * A legend line. The binding is null when none was written, which is right exactly when the
     * entry is a single term.
     */
    public record Entry(char glyph, @Nullable Binding binding, List<Term> terms, int line, int column) {
        public Entry {
            terms = List.copyOf(terms);
        }
    }

    /** One term of a union: a slot reference with an optional form, or a bare name. */
    public sealed interface Term permits SlotRef, Named {
        Map<String, String> props();

        int line();

        int column();
    }

    public record SlotRef(int slot, @Nullable String form, Map<String, String> props, int line, int column)
            implements Term {
        public SlotRef {
            props = Map.copyOf(props);
        }
    }

    /** A bare word — a material inside {@code materials}, a block id everywhere else. */
    public record Named(String name, Map<String, String> props, int line, int column) implements Term {
        public Named {
            props = Map.copyOf(props);
        }
    }

    /**
     * A {@code groups} line: {@code beds required one two three four}.
     *
     * @param columns each variant's column, in order
     */
    public record GroupDecl(String name, boolean required, List<String> variants, List<Integer> columns, int line,
                            int column) {
        public GroupDecl {
            variants = List.copyOf(variants);
            columns = List.copyOf(columns);
        }
    }

    /**
     * A {@code groups} line {@code beds.four needs wing.east}: the variant is only chosen with the
     * others.
     *
     * @param targets {@code group.variant}, as written
     */
    public record NeedDecl(String variant, List<String> targets, List<Integer> columns, int line, int column) {
        public NeedDecl {
            targets = List.copyOf(targets);
            columns = List.copyOf(columns);
        }
    }

    /**
     * A {@code layer} or {@code node layer} grid.
     *
     * @param indices the layers it claims; exactly one for a node grid
     * @param variant the {@code group.variant} whose overlay this is, null for the base
     */
    public record Grid(boolean nodes, List<Integer> indices, @Nullable String variant, int variantColumn,
                       List<Row> rows, int line) {
        public Grid {
            indices = List.copyOf(indices);
            rows = List.copyOf(rows);
        }
    }

    /**
     * @param cells  the row after its margin, comment and id list are cut, right-trimmed
     * @param ids    the node ids after {@code <}, in order
     * @param column the 1-based source column of the first cell
     */
    public record Row(String cells, List<String> ids, int line, int column) {
        public Row {
            ids = List.copyOf(ids);
        }
    }

    public record NodeDecl(String id, String type, int line, int column) {
    }

    public record PathChain(List<String> ids, List<Integer> columns, int line) {
        public PathChain {
            ids = List.copyOf(ids);
            columns = List.copyOf(columns);
        }
    }
}
