package dev.luizloyola.autarkia.core.bp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.autarkia.core.bp.BpCompiler.Compiled;
import dev.luizloyola.autarkia.core.bp.Variants.Selection;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Groups of variants: what a selection composes to, which are allowed, and how each is checked. */
class VariantsTest {

    private static final String HEAD = """
            bp 1
            name        t
            author      t
            version     1
            orientation all
            flippable   false
            """;

    private static Blueprint hut() throws IOException, java.net.URISyntaxException {
        Path file = CorpusTest.files("/bp/valid").filter(p -> p.endsWith("variants.bp")).findFirst().orElseThrow();
        Compiled compiled = BpCompiler.compile("hut", Files.readString(file, StandardCharsets.UTF_8), CorpusTest.DICT);
        assertNotNull(compiled.blueprint(), compiled.diagnostics()::toString);
        return compiled.blueprint();
    }

    private static Compiled compile(String text) {
        return BpCompiler.compile("t", HEAD + text, CorpusTest.DICT);
    }

    @Test
    void aSelectionLaysItsVariantsOverTheBase() throws Exception {
        Blueprint hut = hut();
        Blueprint small = hut.compose(new Selection(Map.of("beds", "one")));
        assertEquals('#', small.glyph(1, 4, 2));
        assertEquals(Blueprint.ANY, small.glyph(1, 6, 2));
        assertEquals('B', small.glyph(1, 1, 2));
        assertEquals('.', small.glyph(1, 3, 2));

        Blueprint grown = hut.compose(new Selection(Map.of("wing", "east", "beds", "three")));
        assertEquals('.', grown.glyph(1, 4, 2), "the wing cuts its doorway from the base's wall");
        assertEquals('B', grown.glyph(1, 6, 2), "the third bed is laid over the wing that it needs");
        assertEquals('#', grown.glyph(1, 8, 2));
        assertTrue(grown.variants().isEmpty());
        assertEquals(List.of("wing.east", "beds.three"), grown.selection().keys());
    }

    /** Declared first, the lamp still goes on after the room it needs, so its cell is the lamp's. */
    @Test
    void aVariantIsLaidOverWhatItNeedsWhateverTheGroupOrder() {
        Compiled lit = compile("""
                groups
                  lamp optional hung
                  room optional big
                  lamp.hung needs room.big
                legend
                  # stone
                  L lantern
                layer 0
                  ###
                layer 1
                  ###
                layer 2
                  ???
                layer 1 room.big
                  #.#
                layer 2 room.big
                  ###
                layer 1 lamp.hung
                  ?L?
                """);
        assertTrue(lit.ok(), lit.diagnostics()::toString);
        Blueprint both = lit.blueprint().compose(new Selection(Map.of("lamp", "hung", "room", "big")));
        assertEquals('L', both.glyph(1, 1, 0));
    }

    @Test
    void theBoxIsTheBuildingAtItsLargest() throws Exception {
        Blueprint hut = hut();
        assertEquals(9, hut.width());
        assertEquals(-2, hut.minLayer());
        assertEquals(3, hut.maxLayer());
        assertEquals(0, hut.baseMinLayer());
        assertEquals(Blueprint.ANY, hut.glyph(-1, 1, 1), "the base leaves the cellar's layers alone");
        Blueprint cellar = hut.compose(new Selection(Map.of("beds", "one", "cellar", "storage")));
        assertEquals('.', cellar.glyph(-1, 1, 1));
        assertEquals('T', cellar.glyph(0, 2, 2));
    }

    @Test
    void everySelectionKeepsItsNeedsAndItsRequiredGroups() throws Exception {
        List<Selection> allowed = hut().variants().selections();
        // wing {-, east} x beds {one, two, three} x cellar {-, storage}, less beds.three without the wing.
        assertEquals(10, allowed.size());
        assertTrue(allowed.stream().allMatch(s -> s.chosen().containsKey("beds")));
        assertTrue(allowed.stream().filter(s -> "three".equals(s.chosen().get("beds")))
                .allMatch(s -> "east".equals(s.chosen().get("wing"))));
    }

    @Test
    void aFileWithNoGroupsIsItsOwnOnlySelection() throws IOException {
        Blueprint house = BpCompiler.compile("h", ChecksAndFactsTest.house(), CorpusTest.DICT).blueprint();
        assertTrue(house.variants().isEmpty());
        assertEquals(List.of(Selection.BASE), house.variants().selections());
        assertSame(house, house.compose(Selection.BASE));
    }

    @Test
    void aProblemInSomeSelectionsSaysWhich() {
        Compiled bunk = compile("""
                groups
                  bunk optional one two
                legend
                  # stone
                  B red_bed[facing=south]
                layer 0
                  ###
                  ###
                layer 1
                  #..
                  ...
                layer 1 bunk.one
                  ?B?
                  ???
                layer 1 bunk.two
                  ???
                  ?B?
                """);
        assertFalse(bunk.ok());
        Diagnostic outside = bunk.diagnostics().stream().filter(d -> d.code().equals("fixture_outside"))
                .findFirst().orElseThrow();
        assertTrue(outside.message().endsWith(" — with bunk.one"), outside.message());

        Compiled shelf = compile("""
                groups
                  shelf optional high
                legend
                  # stone
                layer 0
                  ###
                layer 1
                  #..
                layer 2 shelf.high
                  ??#
                """);
        assertTrue(shelf.ok(), shelf.diagnostics()::toString);
        Diagnostic floating = shelf.diagnostics().stream().filter(d -> d.code().equals("unsupported"))
                .findFirst().orElseThrow();
        assertTrue(floating.message().endsWith(" — with shelf.high"), floating.message());
    }

    @Test
    void tooManySelectionsIsRefusedRatherThanHalfChecked() {
        StringBuilder groups = new StringBuilder("groups\n");
        for (char g = 'a'; g <= 'e'; g++) {
            groups.append("  ").append(g).append(" optional one two three four\n");
        }
        Compiled many = compile(groups + """
                legend
                  # stone
                layer 0
                  #
                """);
        assertFalse(many.ok());
        assertEquals(List.of("variants_too_many"), many.diagnostics().stream().map(Diagnostic::code).toList());
    }
}
