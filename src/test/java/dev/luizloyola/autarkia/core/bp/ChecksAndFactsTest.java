package dev.luizloyola.autarkia.core.bp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.autarkia.core.bp.Blueprint.Facing;
import dev.luizloyola.autarkia.core.bp.BpCompiler.Compiled;
import dev.luizloyola.autarkia.core.bp.Facts.Entrance;
import dev.luizloyola.autarkia.core.bp.Facts.Need;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The warnings a file still loads with, and the facts read off its grid. */
class ChecksAndFactsTest {

    private static final Dictionary DICT = CorpusTest.DICT;

    private static final String HEAD = """
            bp 1
            name        t
            author      t
            version     1
            orientation all
            flippable   false
            """;

    static String house() throws IOException {
        try (InputStream in = ChecksAndFactsTest.class.getResourceAsStream(
                "/data/autarkia/autarkia/blueprint/basic_wooden_house.bp")) {
            assertNotNull(in);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Compiled compile(String text) {
        return BpCompiler.compile("t", text, DICT);
    }

    private static List<String> codes(Compiled compiled) {
        return compiled.diagnostics().stream().map(Diagnostic::code).sorted().toList();
    }

    /**
     * Fence windows, a stair roof and an eave over the doorstep, on any ground: still one room, a
     * front door, and the room and the porch roofed.
     */
    @Test
    void aStairRoofAndFenceWindowsCloseTheRoomAndAPorchKeepsTheDoor() {
        Compiled compiled = compile(HEAD + """
                legend
                  # stone
                  S oak_stairs
                  s oak_stairs[facing=south]
                  F oak_fence
                  D oak_door[hinge=right]
                layer 0
                  ~~~~~
                  ~~~~~
                  ~~~~~
                  ~~~~~
                  ~~~~~
                  ~~~~~
                layer 1
                  #####
                  #...#
                  #...#
                  #...#
                  ##D##
                  .....
                layer 2
                  #####
                  #...#
                  F...F
                  #...#
                  ##.##
                  .....
                layer 3
                  SSSSS
                  SSSSS
                  SSSSS
                  sssss
                  sssss
                  sssss
                """);
        assertTrue(compiled.ok(), compiled.diagnostics()::toString);
        Facts facts = Facts.of(compiled.blueprint(), DICT);
        assertEquals(1, facts.rooms());
        assertEquals(List.of(new Entrance(new Diagnostic.Cell(1, 2, 4), Facing.SOUTH)), facts.entrances());
        assertEquals(2 * (9 + 5), facts.roofed());
    }

    @Test
    void theHouseKnowsItsFrontDoorBedAndTorch() throws IOException {
        Compiled compiled = compile(house());
        Facts facts = Facts.of(compiled.blueprint(), DICT);
        assertEquals(5, facts.width());
        assertEquals(5, facts.depth());
        assertEquals(-1, facts.minLayer());
        assertEquals(4, facts.maxLayer());
        assertEquals(List.of(new Entrance(new Diagnostic.Cell(1, 2, 4), Facing.SOUTH)), facts.entrances());
        assertEquals(1, facts.beds());
        assertEquals(1, facts.lights());
        // The window band lets the outside in, so no room is sealed — but it is roofed.
        assertEquals(0, facts.rooms());
        assertTrue(facts.roofed() > 20, "roofed " + facts.roofed());
        assertEquals(1, facts.countsInLegendOrder(compiled.blueprint()).get('D'));
    }

    @Test
    void theSpecsOwnDoorWasAlongItsWall() throws IOException {
        Compiled compiled = compile(house().replace("door[facing=north,hinge=right]", "door[facing=west,hinge=right]"));
        assertEquals(List.of("door_across_wall"), codes(compiled));
        Diagnostic door = compiled.diagnostics().get(0);
        assertEquals(new Diagnostic.Cell(1, 2, 4), door.cell());
        assertTrue(door.message().contains("facing=north or south fits the gap"), door.message());
        assertTrue(compiled.ok(), "a warning, not an error");
    }

    @Test
    void aBlockTouchingNothingThatReachesTheGroundIsReported() {
        Compiled compiled = compile(HEAD + """

                legend
                  # stone

                layer 0
                  ###

                layer 1
                  ...

                layer 2
                  ..#
                """);
        assertEquals(List.of("unsupported"), codes(compiled));
        assertEquals(new Diagnostic.Cell(2, 2, 0), compiled.diagnostics().get(0).cell());
    }

    @Test
    void aSealedRoomWithNoDoorIsReportedAndOneWithADoorIsARoom() {
        String box = HEAD + """

                legend
                  # stone
                  D oak_door

                layer 0 3 4
                  #####
                  #####
                  #####

                layer 1
                  #####
                  #...#
                  ##X##

                layer 2
                  #####
                  #...#
                  ##Y##
                """;
        Compiled sealed = compile(box.replace('X', '#').replace('Y', '#'));
        // Sealed, the door's glyph is never drawn, and saying so is right too.
        assertEquals(List.of("no_way_in", "unused_glyph"), codes(sealed));
        Compiled withDoor = compile(box.replace('X', 'D').replace('Y', '.'));
        assertEquals(List.of(), codes(withDoor));
        Facts facts = Facts.of(withDoor.blueprint(), DICT);
        assertEquals(1, facts.rooms());
        assertEquals(6, facts.roomCells());
        assertEquals(1, facts.entrances().size());
        assertEquals(Facing.SOUTH, facts.entrances().get(0).outward());
    }

    @Test
    void aColumnThatPlacesNothingButClearsAirIsReportedOnce() {
        Compiled compiled = compile(HEAD + """

                legend
                  # stone

                layer 0
                  #.#.

                layer 1
                  #...
                """);
        assertEquals(List.of("clears_open_column"), codes(compiled));
        assertTrue(compiled.diagnostics().get(0).message().startsWith("2 columns"));
    }

    @Test
    void sandOverAirFalls() {
        Compiled compiled = compile(HEAD + """

                legend
                  # stone
                  s sand

                layer 0
                  ##

                layer 1
                  #.

                layer 2
                  ss
                """);
        assertEquals(List.of("falls"), codes(compiled));
        assertEquals(new Diagnostic.Cell(2, 1, 0), compiled.diagnostics().get(0).cell());
    }

    @Test
    void aPathNodeOnNoPathIsLonely() {
        Compiled compiled = compile(HEAD + """

                legend
                  # stone

                layer 0
                  ###

                layer 1
                  @.@  < a b

                nodes
                  a path_node
                  b path_node
                """);
        assertEquals(List.of("lonely_node", "lonely_node"), codes(compiled));
    }

    @Test
    void anEntryNeedsANodeOnlyWhenEveryWayOfBuildingItIsGated() {
        Compiled compiled = compile(HEAD + """

                palette
                  1 option dirt_path|cobblestone

                legend
                  # $1
                  c cobblestone

                layer 0
                  #c
                """);
        Map<String, String> gates = Map.of("minecraft:cobblestone", "autarkia:stone");
        List<Need> needs = Facts.needs(compiled.blueprint(), DICT, item -> Optional.ofNullable(gates.get(item)));
        assertEquals(List.of(new Need('c', Set.of("autarkia:stone"))), needs);
    }

    @Test
    void aFullLegendIsAnError() {
        StringBuilder text = new StringBuilder(HEAD).append("\nlegend\n");
        int count = 0;
        for (char glyph = '!'; count <= Binder.LEGEND_CAP; glyph++) {
            if (BpParser.RESERVED.indexOf(glyph) >= 0) {
                continue;
            }
            text.append("  ").append(glyph).append(" stone\n");
            count++;
        }
        text.append("\nlayer 0\n  !\n");
        assertEquals(List.of("legend_full"), codes(compile(text.toString())));
    }
}
