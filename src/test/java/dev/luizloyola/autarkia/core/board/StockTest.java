package dev.luizloyola.autarkia.core.board;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import dev.luizloyola.anima.core.inv.ItemSpec;
import java.util.Set;
import org.junit.jupiter.api.Test;

class StockTest {

    /** A build step placing dirt asks for the item alone; it must not get, or take, the family's spec. */
    @Test
    void theDirtFamilyIsNotTheDirtItem() {
        ItemSpec item = ItemSpec.anyOf(Set.of("minecraft:dirt"));
        assertNotSame(Stock.DIRT, item);
        assertNotEquals(Stock.DIRT.name(), item.name());
    }
}
