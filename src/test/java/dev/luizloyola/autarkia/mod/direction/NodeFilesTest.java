package dev.luizloyola.autarkia.mod.direction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import dev.luizloyola.anima.core.agent.FoodValue;
import dev.luizloyola.anima.core.brain.sense.FoodLookup;
import dev.luizloyola.anima.core.brain.task.ReadyFood;
import dev.luizloyola.anima.core.inv.ItemStack;
import dev.luizloyola.autarkia.core.direction.AreaLine;
import dev.luizloyola.autarkia.core.direction.BaseLine;
import dev.luizloyola.autarkia.core.direction.DirectionId;
import dev.luizloyola.autarkia.core.direction.FoodLine;
import dev.luizloyola.autarkia.core.direction.Lines;
import dev.luizloyola.autarkia.core.direction.Node;
import dev.luizloyola.autarkia.core.direction.NodeKind;
import dev.luizloyola.autarkia.core.direction.StorageLine;
import dev.luizloyola.autarkia.core.direction.Tree;
import dev.luizloyola.autarkia.core.direction.WoodLine;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The node files as written: the ones this mod ships load into a tree that passes every rule, and
 * a typo is refused rather than read as something else.
 */
class NodeFilesTest {

    @BeforeEach
    void lines() {
        Lines.register(AreaLine.INSTANCE);
        Lines.register(WoodLine.INSTANCE);
        Lines.register(BaseLine.INSTANCE);
        Lines.register(StorageLine.INSTANCE);
        Lines.register(FoodLine.INSTANCE);
    }

    @AfterEach
    void clear() {
        Lines.clear();
        ReadyFood.install(null);
    }

    private static NodeFiles.NodeFile shipped(String path) throws IOException {
        String resource = "/data/autarkia/" + NodeFiles.DIRECTORY + "/" + path + ".json";
        try (InputStream in = NodeFilesTest.class.getResourceAsStream(resource)) {
            assertTrue(in != null, "missing " + resource);
            return NodeFiles.parse("autarkia:" + path,
                    JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
        }
    }

    /** A file as a node, its tags left unresolved — there is no registry in a unit test. */
    private static Node unresolved(NodeFiles.NodeFile file) {
        return new Node(file.id(), file.kind(), file.parents(), file.needsAll(), file.requirements(),
                file.directions(), Set.of(), Set.copyOf(file.acts()));
    }

    @Test
    void theShippedTreePassesEveryRule() throws IOException {
        NodeFiles.NodeFile wood = shipped("wood");
        NodeFiles.NodeFile stone = shipped("stone");
        assertEquals(List.of("#autarkia:node/wood"), wood.items());
        assertEquals(new DirectionId("autarkia:wood", "wood"), stone.requirements().key());

        List<Node> nodes = new ArrayList<>(List.of(unresolved(wood), unresolved(stone)));
        // A server installs what is food as it starts, before the tree is built; this is that.
        ReadyFood.install(new FoodLookup() {
            @Override
            public Optional<FoodValue> of(ItemStack stack) {
                return stack.id().equals("minecraft:sweet_berries")
                        ? Optional.of(new FoodValue(2, 0.4F, false)) : Optional.empty();
            }

            @Override
            public Optional<FoodValue> cookedForm(ItemStack stack) {
                return Optional.empty();
            }
        });
        Tree.Built built = Tree.build(nodes, Set.of("minecraft:oak_log", "minecraft:sweet_berries"),
                key -> false);
        assertTrue(built.errors().isEmpty(), () -> "the shipped tree is refused: " + built.errors());
        assertEquals(Set.of("autarkia:wood"), built.tree().roots());
        assertEquals(NodeKind.CORE, built.tree().node("autarkia:stone").orElseThrow().kind());
    }

    @Test
    void anUnknownFieldIsATypoNotAnExtension() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> NodeFiles.parse("test:x", JsonParser.parseString("{\"parent\": [\"a:b\"]}")));
        assertTrue(refused.getMessage().contains("parent"));
    }

    @Test
    void aDirectionWithNoLineSaysSo() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> NodeFiles.parse("test:x", JsonParser.parseString("{\"directions\": [{\"count\": 3}]}")));
        assertEquals("a direction names its line", refused.getMessage(),
                "the server log carries this message, so it has to say something");
    }

    @Test
    void aRequirementIsANodeThenItsLine() {
        NodeFiles.NodeFile file = NodeFiles.parse("test:x", JsonParser.parseString(
                "{\"parents\": [\"my:pack/wood\"], \"requires\": {\"pool\": [\"my:pack/wood/area\"]}}"));
        assertEquals(new DirectionId("my:pack/wood", "area"), file.requirements().pool().get(0),
                "the line is after the LAST slash, so a node id may have a path of its own");
        assertEquals(1, file.requirements().of(), "of defaults to the whole pool");
    }
}
