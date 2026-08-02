package dev.luizloyola.autarkia.core.tree;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.knowledge.BlockKind;
import dev.luizloyola.anima.core.brain.knowledge.FakeProbe;
import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Headless tests for the seam finder — the split's boundary, cell pair by cell pair. */
class TreeSeamsTest {

    private final FakeProbe probe = new FakeProbe();
    private final Map<Pos, BlockKind> mass = new LinkedHashMap<>();

    @Test
    void fusedOaksMeetExactlyAlongTheirCanopyPlane() {
        probe.placeOak(0, 0);
        probe.placeOak(3, 0);
        TreeMassesTest.oakInto(mass, 0, 0);
        TreeMassesTest.oakInto(mass, 3, 0);

        List<TreeSeams.Contact> contacts = TreeSeams.contacts(TreeShape.split(mass, probe));

        // The two crowns touch on one vertical plane: ring row y67 and cap row y68, z -1..1.
        assertEquals(6, contacts.size());
        for (TreeSeams.Contact contact : contacts) {
            assertEquals(1, contact.cell().x(), "every seam cell sits on tree 0's rim");
            assertEquals(2, contact.other().x(), "and faces tree 1's rim one step east");
            assertEquals(0, contact.tree(), "the west side belongs to the west oak");
            assertEquals(1, contact.otherTree(), "the east side belongs to the east oak");
        }
    }

    @Test
    void aLoneTreeHasNoSeam() {
        probe.placeOak(0, 0);
        TreeMassesTest.oakInto(mass, 0, 0);
        assertTrue(TreeSeams.contacts(TreeShape.split(mass, probe)).isEmpty(),
                "self-adjacency is not a seam");
    }
}
