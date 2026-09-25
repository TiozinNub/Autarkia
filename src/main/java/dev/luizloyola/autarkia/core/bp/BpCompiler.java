package dev.luizloyola.autarkia.core.bp;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Parse, bind and check in one call — what the game's library, the offline checker and the tests
 * all run, so the three can never disagree about a file.
 */
public final class BpCompiler {

    private BpCompiler() {
    }

    /** One file's outcome. A broken blueprint keeps its diagnostics so it can explain itself. */
    public record Compiled(String id, @Nullable Blueprint blueprint, List<Diagnostic> diagnostics) {
        public Compiled {
            diagnostics = List.copyOf(diagnostics);
        }

        public boolean ok() {
            return blueprint != null;
        }

        public long errors() {
            return diagnostics.stream().filter(Diagnostic::isError).count();
        }

        public long reports() {
            return diagnostics.size() - errors();
        }
    }

    public static Compiled compile(String id, String text, Dictionary dict) {
        Diagnostics out = new Diagnostics();
        BpSource source = BpParser.parse(text, out);
        Blueprint blueprint = Binder.bind(id, source, dict, out);
        if (blueprint != null) {
            Checks.run(blueprint, dict, out);
        }
        return new Compiled(id, blueprint, out.list());
    }
}
