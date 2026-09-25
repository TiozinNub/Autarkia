package dev.luizloyola.autarkia.core.bp;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One thing worth saying about a blueprint. Literal English, like a TOML parser's complaint; the
 * {@code code} is the stable part, and what tests compare.
 *
 * @param line   1-based source line, 0 when the problem belongs to the file as a whole
 * @param column 1-based, 0 when the whole line is meant
 * @param cell   the grid cell a grid problem is about — worth more than a column in a row of hashes
 */
public record Diagnostic(Severity severity, String code, String message, int line, int column,
                         @Nullable Cell cell) {

    public enum Severity {
        /** The blueprint cannot load. */
        ERROR,
        /** It loads, but something is worth saying. */
        REPORT
    }

    /** A cell in the drawing's own coordinates: x west→east, z north→south, the layer as numbered. */
    public record Cell(int layer, int x, int z) {
        @Override
        public String toString() {
            return "layer " + layer + " (" + x + "," + z + ")";
        }
    }

    public Diagnostic {
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
    }

    public boolean isError() {
        return severity == Severity.ERROR;
    }

    /** {@code 12:5: error [row_width] …}, the shape a log line and the offline checker print. */
    public String summary() {
        StringBuilder out = new StringBuilder();
        if (line > 0) {
            out.append(line);
            if (column > 0) {
                out.append(':').append(column);
            }
            out.append(": ");
        }
        out.append(severity == Severity.ERROR ? "error" : "report")
                .append(" [").append(code).append("] ");
        if (cell != null) {
            out.append(cell).append(": ");
        }
        return out.append(message).toString();
    }
}
