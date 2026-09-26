package dev.luizloyola.autarkia.core.bp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.autarkia.core.bp.Blueprint.Facing;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The words after the id: pins, a facing and flip, in any order. */
class PlanArgsTest {

    private static List<String> codes(Diagnostics out) {
        return out.list().stream().map(Diagnostic::code).toList();
    }

    private static Blueprint bp(String orientation, boolean flippable) {
        String text = """
                bp 1
                name        t
                author      t
                version     1
                orientation %s
                flippable   %s
                legend
                  # stone
                layer 1
                  #
                """.formatted(orientation, flippable);
        return BpCompiler.compile("t", text, CorpusTest.DICT).blueprint();
    }

    @Test
    void wordsComeInAnyOrderWithCommasOrSpaces() {
        Diagnostics out = new Diagnostics();
        PlanArgs args = PlanArgs.parse(" 1=spruce,2=red  east flip 3=minecraft:gravel ", out);
        assertTrue(out.list().isEmpty(), out.list()::toString);
        assertEquals(Map.of(1, "spruce", 2, "red", 3, "minecraft:gravel"), args.pins());
        assertEquals(Facing.EAST, args.facing());
        assertTrue(args.flip());
    }

    @Test
    void anythingElseIsNamed() {
        Diagnostics out = new Diagnostics();
        PlanArgs.parse("up 1= =3 1=oak 1=spruce north south", out);
        assertEquals(List.of("arg_unknown", "arg_unknown", "arg_unknown", "pin_twice", "facing_twice"), codes(out));
    }

    @Test
    void withNoFacingTheDrawingGoesAsDrawnWhenItMay() {
        Diagnostics out = new Diagnostics();
        assertEquals(Placement.AS_DRAWN, PlanArgs.NONE.placement(bp("north east", false), out));
        assertEquals(new Placement(Facing.EAST, false), PlanArgs.NONE.placement(bp("west east", false), out));
        assertTrue(out.list().isEmpty());
    }

    @Test
    void theHeadersRefuseWhatTheyForbid() {
        Diagnostics out = new Diagnostics();
        assertNull(new PlanArgs(Map.of(), Facing.WEST, false).placement(bp("north east", true), out));
        assertNull(new PlanArgs(Map.of(), null, true).placement(bp("all", false), out));
        assertEquals(List.of("facing_refused", "flip_refused"), codes(out));
        assertFalse(out.list().get(0).message().contains("west,"), out.list().get(0).message());
    }
}
