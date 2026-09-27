package dev.luizloyola.autarkia.core.bp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.autarkia.core.bp.BpCompiler.Compiled;
import dev.luizloyola.autarkia.core.bp.Variants.Selection;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
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

    // ── planning ────────────────────────────────────────────────────────────────────────────

    private static BuildPlan plan(Blueprint bp, Map<String, String> variants, Chooser chooser, Diagnostics out) {
        return Planner.plan(bp, CorpusTest.DICT, Support.solidCubes(CorpusTest.DICT), Map.of(1, "oak"), variants,
                chooser, new Random(1), out);
    }

    @Test
    void aPinnedVariantBringsWhatItNeedsAndThePlanSaysSo() throws Exception {
        Diagnostics out = new Diagnostics();
        List<Chooser.Choice> offered = new ArrayList<>();
        BuildPlan plan = plan(hut(), Map.of("beds", "three"), (what, choices) -> {
            assertEquals("variants", what);
            offered.addAll(choices);
            return 0;
        }, out);
        assertNotNull(plan, out.list()::toString);
        // beds.three needs the wing, so only the cellar is left to choose: without it, or with it.
        assertEquals(List.of("wing.east, beds.three", "wing.east, beds.three, cellar.storage"),
                offered.stream().map(Chooser.Choice::label).toList());
        assertEquals(Map.of("wing", "east", "beds", "three", "cellar", "none"), plan.variants());
        assertEquals("wing=east beds=three cellar=none 1=oak", plan.pins());
        assertEquals(3, plan.bill().stream().filter(line -> line.items().contains("minecraft:red_bed"))
                .mapToInt(BuildPlan.BillLine::count).sum());
        assertEquals("minecraft:red_bed", plan.state(1, 6, 2).block());
    }

    @Test
    void unpinnedEveryAllowedSelectionIsOffered() throws Exception {
        List<Chooser.Choice> offered = new ArrayList<>();
        Diagnostics out = new Diagnostics();
        BuildPlan plan = plan(hut(), Map.of(), (what, choices) -> {
            offered.addAll(choices);
            return choices.size() - 1;
        }, out);
        assertNotNull(plan, out.list()::toString);
        assertEquals(10, offered.size());
        assertTrue(offered.stream().allMatch(choice -> choice.weight() == 0.1));
        assertEquals(Map.of("wing", "east", "beds", "three", "cellar", "storage"), plan.variants());
    }

    @Test
    void aPinTheFileCannotHonourIsRefusedWithTheReason() throws Exception {
        Blueprint hut = hut();
        Chooser never = (what, choices) -> {
            throw new AssertionError("refused before choosing");
        };
        Map<String, String> needsTheWing = new LinkedHashMap<>();
        needsTheWing.put("beds", "three");
        needsTheWing.put("wing", "none");
        Map<Map<String, String>, String> refusals = Map.of(
                needsTheWing, "pin_needs: no selection has beds=three wing=none: beds.three needs wing.east",
                Map.of("beds", "none"), "pin_required: group 'beds' is required: pin one of one, two, three",
                Map.of("bed", "two"), "pin_group: no group 'bed' — did you mean 'beds'?; it has wing, beds, cellar",
                Map.of("beds", "four"), "pin_variant: group 'beds' has one, two, three — not 'four'");
        refusals.forEach((pins, said) -> {
            Diagnostics out = new Diagnostics();
            assertNull(plan(hut, pins, never, out));
            assertEquals(List.of(said), out.list().stream().map(d -> d.code() + ": " + d.message()).toList());
        });
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
