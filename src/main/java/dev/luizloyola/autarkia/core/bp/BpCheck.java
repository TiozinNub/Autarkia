package dev.luizloyola.autarkia.core.bp;

import dev.luizloyola.autarkia.core.bp.BpCompiler.Compiled;
import dev.luizloyola.autarkia.core.bp.Facts.Entrance;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The offline checker: binds {@code .bp} files against a dictionary the game exported, with no game
 * running, through the same {@link BpCompiler} the game uses. Run by {@code scripts/bp-check.sh}.
 *
 * <pre>BpCheck [--facts] &lt;dictionary.bpdict&gt; &lt;file.bp | directory&gt;…</pre>
 *
 * Exits 1 when any file has an error, 2 on a usage problem.
 */
public final class BpCheck {

    private BpCheck() {
    }

    public static void main(String[] args) throws IOException {
        System.exit(run(List.of(args), System.out));
    }

    static int run(List<String> args, PrintStream out) throws IOException {
        List<String> rest = new ArrayList<>(args);
        boolean facts = rest.remove("--facts");
        if (rest.size() < 2) {
            out.println("usage: BpCheck [--facts] <dictionary.bpdict> <file.bp | directory>...");
            return 2;
        }
        Dictionary dict;
        try {
            dict = DictionaryFile.read(Files.readString(Path.of(rest.get(0)), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            out.println(rest.get(0) + ": " + e.getMessage());
            return 2;
        }
        List<Path> files = new ArrayList<>();
        for (String arg : rest.subList(1, rest.size())) {
            Path path = Path.of(arg);
            if (Files.isDirectory(path)) {
                try (Stream<Path> walk = Files.walk(path)) {
                    walk.filter(p -> p.toString().endsWith(".bp")).sorted().forEach(files::add);
                }
            } else {
                files.add(path);
            }
        }
        int broken = 0;
        for (Path file : files) {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            String name = file.getFileName().toString();
            Compiled compiled = BpCompiler.compile(name.substring(0, name.length() - ".bp".length()), text, dict);
            List<String> lines = DiagnosticText.lines(text);
            compiled.diagnostics().forEach(d -> out.println(DiagnosticText.render(file.toString(), lines, d)));
            out.println(file + ": " + (compiled.ok() ? "ok" : "broken") + ", " + compiled.errors() + " error(s), "
                    + compiled.reports() + " report(s)");
            if (!compiled.ok()) {
                broken++;
            } else if (facts) {
                facts(compiled.blueprint(), dict).forEach(line -> out.println("  " + line));
            }
        }
        return broken > 0 ? 1 : 0;
    }

    /** The derived facts as plain lines — the same numbers {@code bp query} shows in the game. */
    public static List<String> facts(Blueprint bp, Dictionary dict) {
        Facts facts = Facts.of(bp, dict);
        List<String> lines = new ArrayList<>();
        lines.add("footprint " + facts.width() + "x" + facts.depth() + ", layers " + facts.minLayer() + ".."
                + facts.maxLayer() + ", " + facts.placed() + " blocks placed");
        lines.add("entrances " + (facts.entrances().isEmpty() ? "none" : facts.entrances().stream()
                .map(Entrance::outward).map(Blueprint.Facing::word).collect(Collectors.joining(", "))));
        lines.add("beds " + facts.beds() + ", rooms " + facts.rooms() + " (" + facts.roomCells() + " cells), "
                + "roofed cells " + facts.roofed() + ", lights " + facts.lights());
        if (!facts.stations().isEmpty()) {
            lines.add("stations " + facts.stations().entrySet().stream()
                    .map(e -> e.getKey() + " x" + e.getValue()).collect(Collectors.joining(", ")));
        }
        for (Map.Entry<Character, Integer> count : facts.countsInLegendOrder(bp).entrySet()) {
            lines.add("  " + count.getKey() + " x" + count.getValue() + "  "
                    + bp.legend().get(count.getKey()).text());
        }
        return lines;
    }
}
