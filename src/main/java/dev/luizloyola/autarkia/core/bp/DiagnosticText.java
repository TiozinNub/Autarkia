package dev.luizloyola.autarkia.core.bp;

import java.util.List;

/**
 * A diagnostic as a terminal shows it: the summary, then the source line with a caret under the
 * column. Chat has no fixed-width font, so the game colours the cell instead of drawing a caret.
 */
public final class DiagnosticText {

    private DiagnosticText() {
    }

    public static String render(String file, List<String> lines, Diagnostic diagnostic) {
        StringBuilder out = new StringBuilder(file).append(':').append(diagnostic.summary());
        int line = diagnostic.line();
        if (line < 1 || line > lines.size()) {
            return out.toString();
        }
        String source = lines.get(line - 1).replace('\t', ' ');
        String number = String.valueOf(line);
        out.append('\n').append(" ".repeat(Math.max(0, 4 - number.length()))).append(number).append(" | ")
                .append(source);
        if (diagnostic.column() > 0) {
            out.append('\n').append("     | ").append(" ".repeat(diagnostic.column() - 1)).append('^');
        }
        return out.toString();
    }

    /** The lines of a file as the parser numbers them. */
    public static List<String> lines(String text) {
        String body = text.startsWith("\uFEFF") ? text.substring(1) : text;
        return List.of(body.replace("\r", "").split("\n", -1));
    }
}
