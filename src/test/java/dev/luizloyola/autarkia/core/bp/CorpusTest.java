package dev.luizloyola.autarkia.core.bp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.autarkia.core.bp.BpCompiler.Compiled;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * The {@code .bp} corpus. Each file states what it should produce as {@code // expect <severity>
 * <code>} comments on the line the diagnostic must point at — or on a line of its own for one about
 * the whole file — so error quality is tested, not just error presence: a diagnostic on the wrong
 * line, an extra one, or a missing one all fail.
 */
class CorpusTest {

    static final Dictionary DICT = TestBlocks.dictionary();

    @TestFactory
    Stream<DynamicTest> validFilesLoadCleanly() throws IOException, URISyntaxException {
        return files("/bp/valid").map(file -> DynamicTest.dynamicTest(file.getFileName().toString(), () -> {
            Compiled compiled = compile(file);
            assertEquals(expected(file), actual(compiled), file.getFileName().toString());
            assertTrue(compiled.ok());
        }));
    }

    @TestFactory
    Stream<DynamicTest> invalidFilesSayExactlyWhatIsWrong() throws IOException, URISyntaxException {
        return files("/bp/invalid").map(file -> DynamicTest.dynamicTest(file.getFileName().toString(), () -> {
            Compiled compiled = compile(file);
            assertEquals(expected(file), actual(compiled), file.getFileName().toString());
            assertFalse(compiled.ok());
        }));
    }

    /** Every blueprint the jar ships binds without a word, against the same vocabulary. */
    @TestFactory
    Stream<DynamicTest> shippedBlueprintsLoadCleanly() throws IOException, URISyntaxException {
        return files("/data/autarkia/autarkia/blueprint").map(file ->
                DynamicTest.dynamicTest(file.getFileName().toString(), () -> {
                    Compiled compiled = compile(file);
                    assertEquals(List.of(), actual(compiled));
                    assertTrue(compiled.ok());
                }));
    }

    static Stream<Path> files(String dir) throws IOException, URISyntaxException {
        URL url = CorpusTest.class.getResource(dir);
        assertNotNull(url, dir);
        try (Stream<Path> list = Files.list(Path.of(url.toURI()))) {
            return list.filter(p -> p.toString().endsWith(".bp")).sorted().toList().stream();
        }
    }

    static Compiled compile(Path file) throws IOException {
        String name = file.getFileName().toString();
        return BpCompiler.compile(name.substring(0, name.length() - 3), Files.readString(file, StandardCharsets.UTF_8),
                DICT);
    }

    static List<String> actual(Compiled compiled) {
        List<String> found = new ArrayList<>();
        for (Diagnostic d : compiled.diagnostics()) {
            found.add((d.isError() ? "error " : "report ") + d.code() + " @" + d.line());
        }
        found.sort(null);
        return found;
    }

    static List<String> expected(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            int at = line.indexOf("// expect ");
            if (at < 0) {
                continue;
            }
            int target = line.substring(0, at).isBlank() ? 0 : i + 1;
            for (String item : line.substring(at + "// expect ".length()).split(",")) {
                expected.add(item.trim() + " @" + target);
            }
        }
        expected.sort(null);
        return expected;
    }
}
