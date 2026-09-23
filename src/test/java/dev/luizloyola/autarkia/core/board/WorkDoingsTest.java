package dev.luizloyola.autarkia.core.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.history.DoingLines;
import dev.luizloyola.anima.core.brain.history.Slot;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.autarkia.core.tree.TreeClearing;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Autarkia's doings have their words, and so does everything a work item puts in their slots. The
 * compiler makes every work item name a doing; this makes every one of them say something.
 */
class WorkDoingsTest {

    private static final Map<String, String> EN =
            DoingLines.load(WorkDoingsTest.class, "/assets/autarkia/lang/en_us.json");

    static {
        Objects.requireNonNull(WorkDoings.GATHERING); // declared, as AutarkiaMod declares them
    }

    @Test
    void everyRememberedDoingOfAutarkiasHasItsLines() {
        assertEquals(List.of(), DoingLines.problems("autarkia.", EN));
    }

    @Test
    void everySlotAutarkiaFillsHasWords() {
        for (Slot slot : List.of(WorkDoings.goods(Stock.LOGS), WorkDoings.goods(Stock.AXES),
                WorkDoings.FOR_THE_YARD, WorkDoings.cleared(TreeClearing.INSTANCE))) {
            assertEquals(Slot.Type.LANG, slot.type(), slot.encode());
            assertTrue(EN.containsKey(slot.value()), slot.value() + " has no line in en_us");
        }
    }

    @Test
    void aLiteralSpecIsNamedByTheGameThroughItsFirstItem() {
        ItemSpec stones = ItemSpec.anyOf(Set.of("minecraft:stone", "minecraft:andesite"));

        assertEquals(Slot.item("minecraft:andesite"), WorkDoings.goods(stones),
                "the gather command's literal has no lang key of ours — the game names the item");
    }
}
