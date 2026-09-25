package dev.luizloyola.autarkia.core.bp;

import dev.luizloyola.autarkia.core.bp.BpSource.Entry;
import dev.luizloyola.autarkia.core.bp.BpSource.Grid;
import dev.luizloyola.autarkia.core.bp.BpSource.Header;
import dev.luizloyola.autarkia.core.bp.BpSource.Named;
import dev.luizloyola.autarkia.core.bp.BpSource.NodeDecl;
import dev.luizloyola.autarkia.core.bp.BpSource.PathChain;
import dev.luizloyola.autarkia.core.bp.BpSource.Row;
import dev.luizloyola.autarkia.core.bp.BpSource.Slot;
import dev.luizloyola.autarkia.core.bp.BpSource.SlotRef;
import dev.luizloyola.autarkia.core.bp.BpSource.Term;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Stage 1: text to {@link BpSource}. Line-oriented, and it never stops at the first problem.
 *
 * <p>A section ends at a blank line or at the next line in column 0. A line holding only a comment
 * is invisible — it neither ends a section nor counts as a row.
 */
public final class BpParser {

    /** Glyphs that may never be a legend or node key. {@code /} because {@code //} is a comment. */
    public static final String RESERVED = ".?~@</ ";

    private static final Pattern ID = Pattern.compile("[a-z0-9_.\\-/]+(:[a-z0-9_.\\-/]+)?");
    private static final Pattern WORD = Pattern.compile("[a-z0-9_]+");
    private static final Pattern NUMBER = Pattern.compile("-?[0-9]+");

    private enum Section { NONE, MATERIALS, PALETTE, LEGEND, GRID, NODES, PATH }

    private final Diagnostics out;
    private int format;
    private boolean seenFirst;
    private Section section = Section.NONE;
    private final List<Header> headers = new ArrayList<>();
    private final List<Slot> materials = new ArrayList<>();
    private final List<Slot> palette = new ArrayList<>();
    private final List<Entry> legend = new ArrayList<>();
    private final List<Grid> grids = new ArrayList<>();
    private final List<NodeDecl> nodes = new ArrayList<>();
    private final List<PathChain> paths = new ArrayList<>();
    private final Set<Integer> brokenSlots = new HashSet<>();
    private final Set<Character> brokenGlyphs = new HashSet<>();

    // The grid being read: its rows are sliced at the margin only once all are in.
    private boolean gridNodes;
    private List<Integer> gridIndices = List.of();
    private int gridLine;
    private final List<RawRow> gridRows = new ArrayList<>();

    private record RawRow(String text, List<String> ids, int line) {
    }

    private BpParser(Diagnostics out) {
        this.out = out;
    }

    public static BpSource parse(String text, Diagnostics out) {
        BpParser parser = new BpParser(out);
        String body = text.startsWith("\uFEFF") ? text.substring(1) : text;
        String[] lines = body.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].endsWith("\r") ? lines[i].substring(0, lines[i].length() - 1) : lines[i];
            parser.line(line, i + 1);
        }
        parser.endSection();
        if (!parser.seenFirst) {
            out.error("missing_format", 0, 0, "the file is empty; it starts with 'bp 1'");
        }
        return new BpSource(parser.format, parser.headers, parser.materials, parser.palette, parser.legend,
                parser.grids, parser.nodes, parser.paths, parser.brokenSlots,
                parser.brokenGlyphs);
    }

    private void line(String raw, int line) {
        int comment = raw.indexOf("//");
        String code = comment >= 0 ? raw.substring(0, comment) : raw;
        if (comment >= 0 && code.isBlank()) {
            return;
        }
        code = stripTrailing(code);
        if (code.isEmpty()) {
            endSection();
            return;
        }
        boolean indented = Character.isWhitespace(code.charAt(0));
        if (!seenFirst) {
            seenFirst = true;
            if (!indented && code.startsWith("bp")) {
                formatLine(code, line);
                return;
            }
            out.error("missing_format", line, 1, "the first line must be 'bp 1'");
        }
        if (!indented) {
            endSection();
            columnZero(code, line);
            return;
        }
        switch (section) {
            case NONE -> out.error("stray_line", line, firstColumn(code),
                    "an indented line outside any section; sections end at a blank line");
            case MATERIALS -> slot(code, line, materials, false);
            case PALETTE -> slot(code, line, palette, true);
            case LEGEND -> entry(code, line);
            case GRID -> row(code, line);
            case NODES -> nodeDecl(code, line);
            case PATH -> path(code, line);
        }
    }

    private void formatLine(String code, int line) {
        String[] words = code.trim().split("\\s+");
        if (words.length != 2 || !words[0].equals("bp") || !NUMBER.matcher(words[1]).matches()) {
            out.error("missing_format", line, 1, "the first line must be 'bp 1'");
            return;
        }
        format = Integer.parseInt(words[1]);
        if (format != 1) {
            out.error("format_version", line, 4, "this reader knows 'bp 1', not 'bp " + format + "'");
        }
    }

    private void columnZero(String code, int line) {
        String[] words = code.trim().split("\\s+");
        switch (words[0]) {
            case "materials" -> openList(Section.MATERIALS, words, line);
            case "palette" -> openList(Section.PALETTE, words, line);
            case "legend" -> openList(Section.LEGEND, words, line);
            case "nodes" -> openList(Section.NODES, words, line);
            case "path" -> openList(Section.PATH, words, line);
            case "layer" -> openGrid(false, code, "layer".length(), line);
            case "node" -> {
                if (words.length < 2 || !words[1].equals("layer")) {
                    out.error("unknown_section", line, 1, "'node' starts a 'node layer <index>' grid");
                    return;
                }
                openGrid(true, code, code.indexOf("layer") + "layer".length(), line);
            }
            case "bp" -> out.error("format_twice", line, 1, "'bp' belongs on the first line only");
            default -> header(code, words, line);
        }
    }

    private void openList(Section kind, String[] words, int line) {
        if (words.length > 1) {
            out.error("section_argument", line, words[0].length() + 2,
                    "'" + words[0] + "' takes nothing on its own line; its entries go below, indented");
        }
        section = kind;
    }

    private void header(String code, String[] words, int line) {
        String key = words[0];
        String value = code.substring(key.length()).trim();
        if (value.isEmpty()) {
            out.error("header_value", line, 1, "header '" + key + "' has no value");
            return;
        }
        headers.add(new Header(key, value, line, 1));
    }

    // ── grids ───────────────────────────────────────────────────────────────────────────────

    private void openGrid(boolean nodeGrid, String code, int from, int line) {
        List<Integer> indices = new ArrayList<>();
        String rest = code.substring(from);
        int offset = from;
        for (String token : rest.split("\\s+")) {
            if (token.isEmpty()) {
                offset++;
                continue;
            }
            int column = code.indexOf(token, offset) + 1;
            offset = column - 1 + token.length();
            int dots = token.indexOf("..");
            if (dots > 0 && NUMBER.matcher(token.substring(0, dots)).matches()
                    && NUMBER.matcher(token.substring(dots + 2)).matches()) {
                int a = Integer.parseInt(token.substring(0, dots));
                int b = Integer.parseInt(token.substring(dots + 2));
                if (a > b) {
                    out.error("layer_index", line, column, "range '" + token + "' runs backwards; write "
                            + b + ".." + a);
                    continue;
                }
                for (int i = a; i <= b; i++) {
                    indices.add(i);
                }
            } else if (NUMBER.matcher(token).matches()) {
                indices.add(Integer.parseInt(token));
            } else {
                out.error("layer_index", line, column, "'" + token + "' is not a layer index or an a..b range");
            }
        }
        if (indices.isEmpty()) {
            out.error("layer_index", line, 1, (nodeGrid ? "'node layer'" : "'layer'") + " needs an index");
        }
        if (nodeGrid && indices.size() > 1) {
            out.error("node_layer_single", line, 1,
                    "a node layer takes a single index — node ids must be unique, and one grid over several "
                            + "layers would mint each id again");
        }
        section = Section.GRID;
        gridNodes = nodeGrid;
        gridIndices = indices;
        gridLine = line;
        gridRows.clear();
    }

    private void row(String code, int line) {
        int lt = code.indexOf('<');
        String cells = stripTrailing(lt >= 0 ? code.substring(0, lt) : code);
        List<String> ids = new ArrayList<>();
        if (lt >= 0) {
            for (String id : code.substring(lt + 1).trim().split("\\s+")) {
                if (!id.isEmpty()) {
                    ids.add(id);
                }
            }
            if (ids.isEmpty()) {
                out.error("row_ids", line, lt + 1, "'<' is followed by no node ids");
            }
        }
        int tab = cells.indexOf('\t');
        if (tab >= 0) {
            out.error("tab_in_row", line, tab + 1,
                    "a tab in a grid row has no reliable width; indent with spaces and draw air as '.'");
            cells = cells.replace('\t', ' ');
        }
        if (cells.isBlank()) {
            out.error("row_empty", line, 1, "a row of only node ids; draw its cells, air as '.'");
            return;
        }
        gridRows.add(new RawRow(cells, ids, line));
    }

    private void closeGrid() {
        if (gridRows.isEmpty()) {
            out.error("grid_empty", gridLine, 1, "this grid has no rows; they go below it, indented");
        }
        int margin = Integer.MAX_VALUE;
        for (RawRow raw : gridRows) {
            margin = Math.min(margin, indent(raw.text()));
        }
        List<Row> rows = new ArrayList<>();
        for (RawRow raw : gridRows) {
            rows.add(new Row(raw.text().substring(margin), raw.ids(), raw.line(), margin + 1));
        }
        grids.add(new Grid(gridNodes, gridIndices, rows, gridLine));
        gridRows.clear();
    }

    private void endSection() {
        if (section == Section.GRID) {
            closeGrid();
        }
        section = Section.NONE;
    }

    // ── slots and the legend ────────────────────────────────────────────────────────────────

    private void slot(String code, int line, List<Slot> into, boolean blocks) {
        int start = indent(code);
        int end = wordEnd(code, start);
        String number = code.substring(start, end);
        if (!number.matches("[0-9]+")) {
            out.error("slot_number", line, start + 1, "a slot is numbered; '" + number + "' is not a number");
            return;
        }
        int bindingStart = skipSpace(code, end);
        int bindingEnd = wordEnd(code, bindingStart);
        Binding binding = binding(code.substring(bindingStart, bindingEnd));
        if (binding == null) {
            brokenSlots.add(Integer.parseInt(number));
            out.error("binding_missing", line, bindingStart + 1,
                    "slot " + number + " states its binding: '" + number + " option …' or '" + number + " mix …'");
            return;
        }
        List<Term> terms = union(code, skipSpace(code, bindingEnd), line, blocks);
        if (terms != null) {
            into.add(new Slot(Integer.parseInt(number), binding, terms, line, start + 1));
        } else {
            brokenSlots.add(Integer.parseInt(number));
        }
    }

    private void entry(String code, int line) {
        int start = indent(code);
        char glyph = code.charAt(start);
        if (start + 1 < code.length() && !Character.isWhitespace(code.charAt(start + 1))) {
            out.error("glyph_width", line, start + 1,
                    "a legend key is one character, then a space: '" + code.substring(start, wordEnd(code, start))
                            + "' is longer");
            return;
        }
        if (glyph < 33 || glyph > 126) {
            out.error("glyph_ascii", line, start + 1, "a glyph is printable ASCII");
            return;
        }
        if (RESERVED.indexOf(glyph) >= 0) {
            out.error("glyph_reserved", line, start + 1, "'" + glyph + "' is reserved and cannot be a legend key");
            return;
        }
        int next = skipSpace(code, start + 1);
        int wordEnd = wordEnd(code, next);
        Binding binding = binding(code.substring(next, wordEnd));
        if (binding != null) {
            next = skipSpace(code, wordEnd);
        }
        if (next >= code.length()) {
            brokenGlyphs.add(glyph);
            out.error("entry_empty", line, start + 1, "legend key '" + glyph + "' names no block");
            return;
        }
        List<Term> terms = union(code, next, line, true);
        if (terms != null) {
            legend.add(new Entry(glyph, binding, terms, line, start + 1));
        } else {
            brokenGlyphs.add(glyph);
        }
    }

    private static @Nullable Binding binding(String word) {
        return switch (word) {
            case "option" -> Binding.OPTION;
            case "mix" -> Binding.MIX;
            default -> null;
        };
    }

    /** A {@code |}-union from {@code from} to the end of the line; null when any term is broken. */
    private @Nullable List<Term> union(String code, int from, int line, boolean blocks) {
        if (from >= code.length()) {
            out.error("empty_term", line, from + 1, "a union needs at least one term");
            return null;
        }
        List<Term> terms = new ArrayList<>();
        boolean broken = false;
        int start = from;
        while (true) {
            int bar = code.indexOf('|', start);
            int end = bar >= 0 ? bar : code.length();
            Term term = term(code.substring(start, end), start, line, blocks);
            if (term == null) {
                broken = true;
            } else {
                terms.add(term);
            }
            if (bar < 0) {
                break;
            }
            start = bar + 1;
        }
        return broken ? null : terms;
    }

    private @Nullable Term term(String text, int offset, int line, boolean blocks) {
        int lead = indent(text);
        String trimmed = text.trim();
        int column = offset + lead + 1;
        if (trimmed.isEmpty()) {
            out.error("empty_term", line, column, "an empty term; '|' separates terms");
            return null;
        }
        Map<String, String> props = Map.of();
        String head = trimmed;
        int bracket = trimmed.indexOf('[');
        if (bracket >= 0) {
            if (!trimmed.endsWith("]")) {
                out.error("props_syntax", line, column + bracket, "properties are written '[key=value,…]' at the "
                        + "end of the term");
                return null;
            }
            if (!blocks) {
                out.error("props_on_material", line, column + bracket, "a material has no properties; they go on "
                        + "the block that uses it");
                return null;
            }
            props = props(trimmed.substring(bracket + 1, trimmed.length() - 1), line, column + bracket + 1);
            if (props == null) {
                return null;
            }
            head = trimmed.substring(0, bracket);
            if (!head.isEmpty() && Character.isWhitespace(head.charAt(head.length() - 1))) {
                out.error("props_syntax", line, column + bracket, "properties attach to the word before them, "
                        + "with no space");
                return null;
            }
        }
        String[] words = head.trim().split("\\s+");
        if (words[0].startsWith("$")) {
            String digits = words[0].substring(1);
            if (!digits.matches("[0-9]+")) {
                out.error("slot_ref", line, column, "'" + words[0] + "' is not a slot reference; slots are "
                        + "numbers, like $1");
                return null;
            }
            if (words.length > 2) {
                out.error("form_words", line, column, "a form is one word: '"
                        + String.join("_", List.of(words).subList(1, words.length)) + "', not '"
                        + String.join(" ", List.of(words).subList(1, words.length)) + "'");
                return null;
            }
            String form = words.length == 2 ? words[1] : null;
            if (form != null && !WORD.matcher(form).matches()) {
                out.error("form_syntax", line, column, "'" + form + "' is not a form name");
                return null;
            }
            return new SlotRef(Integer.parseInt(digits), form, props, line, column);
        }
        if (words.length > 1) {
            out.error("term_words", line, column, "'" + head.trim() + "' is several words; a slot reference is "
                    + "'$N form', anything else is one id");
            return null;
        }
        if (!ID.matcher(words[0]).matches()) {
            out.error("id_syntax", line, column, "'" + words[0] + "' is not an id");
            return null;
        }
        return new Named(words[0], props, line, column);
    }

    private @Nullable Map<String, String> props(String inside, int line, int column) {
        Map<String, String> props = new LinkedHashMap<>();
        for (String pair : inside.split(",", -1)) {
            String[] kv = pair.trim().split("=", -1);
            if (kv.length != 2 || !WORD.matcher(kv[0].trim()).matches() || !WORD.matcher(kv[1].trim()).matches()) {
                out.error("props_syntax", line, column, "'" + pair.trim() + "' is not key=value");
                return null;
            }
            if (props.put(kv[0].trim(), kv[1].trim()) != null) {
                out.error("props_twice", line, column, "property '" + kv[0].trim() + "' is given twice");
                return null;
            }
        }
        return props;
    }

    // ── nodes and paths ─────────────────────────────────────────────────────────────────────

    private void nodeDecl(String code, int line) {
        int start = indent(code);
        String[] words = code.trim().split("\\s+");
        if (words.length != 2) {
            out.error("node_decl", line, start + 1, "a node is declared '<id> <type>'; the type is one "
                    + "snake_case word and takes no arguments in bp 1");
            return;
        }
        nodes.add(new NodeDecl(words[0], words[1], line, start + 1));
    }

    private void path(String code, int line) {
        List<String> ids = new ArrayList<>();
        List<Integer> columns = new ArrayList<>();
        int start = 0;
        while (true) {
            int arrow = code.indexOf('>', start);
            int end = arrow >= 0 ? arrow : code.length();
            String id = code.substring(start, end).trim();
            int column = start + indent(code.substring(start, end)) + 1;
            if (id.isEmpty() || id.contains(" ")) {
                out.error("path_syntax", line, column, "a path is '<id> > <id> [> <id>…]'");
                return;
            }
            ids.add(id);
            columns.add(column);
            if (arrow < 0) {
                break;
            }
            start = arrow + 1;
        }
        if (ids.size() < 2) {
            out.error("path_syntax", line, columns.get(0), "a path joins at least two nodes: 'a > b'");
            return;
        }
        paths.add(new PathChain(ids, columns, line));
    }

    // ── text ────────────────────────────────────────────────────────────────────────────────

    private static int indent(String s) {
        int i = 0;
        while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\t')) {
            i++;
        }
        return i;
    }

    private static int firstColumn(String s) {
        return indent(s) + 1;
    }

    private static int skipSpace(String s, int from) {
        int i = from;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return i;
    }

    private static int wordEnd(String s, int from) {
        int i = from;
        while (i < s.length() && !Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return i;
    }

    private static String stripTrailing(String s) {
        int end = s.length();
        while (end > 0 && Character.isWhitespace(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(0, end);
    }
}
