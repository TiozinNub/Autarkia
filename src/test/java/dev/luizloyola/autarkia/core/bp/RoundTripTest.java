package dev.luizloyola.autarkia.core.bp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.autarkia.core.bp.BpCompiler.Compiled;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Text out and back: {@code bp show}'s rendering parses to the same blueprint, the dictionary file
 * reads back what it wrote, and the offline checker agrees with the game's compiler.
 */
class RoundTripTest {

    private static final Dictionary DICT = CorpusTest.DICT;

    @Test
    void whatShowPrintsLoadsBackToTheSameText() throws IOException, java.net.URISyntaxException {
        for (Path file : CorpusTest.files("/bp/valid").toList()) {
            Compiled first = CorpusTest.compile(file);
            String rendered = BpText.render(first.blueprint());
            Compiled second = BpCompiler.compile("again", rendered, DICT);
            assertTrue(second.ok(), file + ":\n" + rendered + second.diagnostics());
            assertEquals(rendered, BpText.render(second.blueprint()), file.toString());
        }
    }

    @Test
    void identicalLayersFoldIntoOneIndexSet() throws IOException {
        String rendered = BpText.render(BpCompiler.compile("h", ChecksAndFactsTest.house(), DICT).blueprint());
        assertTrue(rendered.contains("\nlayer 0 4\n"), rendered);
        assertTrue(rendered.contains("  D $1 door[facing=north,hinge=right]\n"), rendered);
        assertEquals("0 2 6..8", BpText.indexSet(List.of(0, 2, 6, 7, 8)));
        assertEquals("-3..-1 1", BpText.indexSet(List.of(-3, -2, -1, 1)));
    }

    @Test
    void theDictionaryFileReadsBackWhatItWrote() {
        String text = DictionaryFile.write(DICT, "test");
        Dictionary back = DictionaryFile.read(text);
        assertEquals(DICT.blocks(), back.blocks());
        assertEquals(DICT.materials(), back.materials());
        assertEquals(Optional.of("minecraft:crimson_stem"), back.lookup("minecraft:crimson", "log"));
    }

    @Test
    void aDictionaryFromBeforeObstructsReadsItAsSolid() {
        Dictionary old = DictionaryFile.read("""
                bpdict 1
                block minecraft:stone solid=true light=0 falls=false item=minecraft:stone
                block minecraft:oak_fence solid=false light=0 falls=false item=minecraft:oak_fence
                """);
        assertTrue(old.blocks().get("minecraft:stone").obstructs());
        assertFalse(old.blocks().get("minecraft:oak_fence").obstructs());
        assertTrue(DICT.blocks().get("minecraft:oak_fence").obstructs());
    }

    @Test
    void theOfflineCheckerPointsAtTheCell(@TempDir Path dir) throws IOException {
        Path dict = dir.resolve("dict.bpdict");
        Files.writeString(dict, DictionaryFile.write(DICT, "test"));
        Path bad = dir.resolve("bad.bp");
        Files.writeString(bad, """
                bp 1
                name        bad
                author      t
                version     1
                orientation all
                flippable   false

                legend
                  # oak_plank

                layer 0
                  ##
                """);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int exit = BpCheck.run(List.of(dict.toString(), dir.toString()), new PrintStream(bytes, true,
                StandardCharsets.UTF_8));
        String printed = bytes.toString(StandardCharsets.UTF_8);
        assertEquals(1, exit, printed);
        assertTrue(printed.contains("did you mean 'oak_planks'?"), printed);
        assertTrue(printed.contains("   9 |   # oak_plank\n     |     ^"), printed);

        Files.writeString(bad, Files.readString(bad).replace("oak_plank", "oak_planks"));
        bytes.reset();
        assertEquals(0, BpCheck.run(List.of("--facts", dict.toString(), bad.toString()),
                new PrintStream(bytes, true, StandardCharsets.UTF_8)));
        assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("footprint 2x1"), bytes.toString());
    }

    @Test
    void aNearTypoIsSuggestedAndAFarWordIsNot() {
        assertEquals(Optional.of("oak_planks"), Suggest.closest("oak_plank", List.of("oak_planks", "oak_log")));
        assertEquals(Optional.empty(), Suggest.closest("cobblestone", List.of("oak_planks", "oak_log")));
        assertEquals(Optional.of("path_node"), Suggest.closest("pathnode", List.of("path_node")));
    }

    @Test
    void aGridsMarginIsItsLeastIndentedRow() {
        Diagnostics out = new Diagnostics();
        BpSource source = BpParser.parse("""
                bp 1
                layer 1
                    .#
                   #.#
                   ###
                """, out);
        assertEquals(List.of(), out.list());
        assertEquals(" .#", source.grids().get(0).rows().get(0).cells());
        assertEquals(4, source.grids().get(0).rows().get(0).column());
    }
}
