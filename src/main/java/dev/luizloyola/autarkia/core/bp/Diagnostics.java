package dev.luizloyola.autarkia.core.bp;

import dev.luizloyola.autarkia.core.bp.Diagnostic.Cell;
import dev.luizloyola.autarkia.core.bp.Diagnostic.Severity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Where every stage puts what it found. Never throws: one pass reports everything wrong. */
public final class Diagnostics {

    private final List<Diagnostic> found = new ArrayList<>();

    public void error(String code, int line, int column, String message) {
        found.add(new Diagnostic(Severity.ERROR, code, message, line, column, null));
    }

    public void report(String code, int line, int column, String message) {
        found.add(new Diagnostic(Severity.REPORT, code, message, line, column, null));
    }

    public void cellError(String code, int line, int column, Cell cell, String message) {
        found.add(new Diagnostic(Severity.ERROR, code, message, line, column, cell));
    }

    public void cellReport(String code, int line, int column, @Nullable Cell cell, String message) {
        found.add(new Diagnostic(Severity.REPORT, code, message, line, column, cell));
    }

    public void add(Diagnostic diagnostic) {
        found.add(diagnostic);
    }

    public boolean hasErrors() {
        return found.stream().anyMatch(Diagnostic::isError);
    }

    /** In file order; a problem with no line sorts first, since it is about the whole file. */
    public List<Diagnostic> list() {
        List<Diagnostic> sorted = new ArrayList<>(found);
        sorted.sort(Comparator.comparingInt(Diagnostic::line).thenComparingInt(Diagnostic::column));
        return List.copyOf(sorted);
    }
}
