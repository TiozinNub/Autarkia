package dev.luizloyola.autarkia.core.direction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The tree's load rules, and what a checked tree answers: which version of a line is in force,
 * what a set of checkpoints unlocks, and what a body may not make yet.
 */
class TreeTest {

    private static final String WOOD = "test:wood";
    private static final String STONE = "test:stone";
    private static final String COPPER = "test:copper";

    private static final Set<String> ITEMS = Set.of("minecraft:oak_log", "minecraft:wooden_pickaxe",
            "minecraft:stone_pickaxe", "minecraft:cobblestone");

    @BeforeEach
    void lines() {
        Lines.register(AreaLine.INSTANCE);
        Lines.register(WoodLine.INSTANCE);
    }

    @AfterEach
    void clear() {
        Lines.clear();
    }

    private static DirectionId ref(String node, String line) {
        return new DirectionId(node, line);
    }

    private static Direction direction(String node, String line, int count) {
        return new Direction(ref(node, line), count, null);
    }

    private static Node node(String id, NodeKind kind, List<String> parents, Requirements needs,
                             List<Direction> directions, Set<String> items) {
        return new Node(id, kind, parents, true, needs, directions, items, Set.of());
    }

    private static Node wood() {
        return node(WOOD, NodeKind.CORE, List.of(), Requirements.NONE,
                List.of(direction(WOOD, "area", 0), direction(WOOD, "wood", 64)),
                Set.of("minecraft:wooden_pickaxe"));
    }

    private static Node stone() {
        return node(STONE, NodeKind.CORE, List.of(WOOD),
                new Requirements(ref(WOOD, "wood"), List.of(ref(WOOD, "area")), 1),
                List.of(direction(STONE, "wood", 256)),
                Set.of("minecraft:stone_pickaxe", "minecraft:cobblestone"));
    }

    private static Tree built(Node... nodes) {
        Tree.Built built = Tree.build(List.of(nodes), ITEMS, key -> false);
        assertTrue(built.errors().isEmpty(), () -> "refused: " + built.errors());
        return built.tree();
    }

    private static List<String> refused(Node... nodes) {
        Tree.Built built = Tree.build(List.of(nodes), ITEMS, key -> false);
        assertNull(built.tree(), "a table that breaks a rule is refused whole");
        assertFalse(built.errors().isEmpty());
        return built.errors();
    }

    private static boolean mentions(List<String> errors, String node, String words) {
        return errors.stream().anyMatch(e -> e.startsWith(node + ":") && e.contains(words));
    }

    // ── load rules ──────────────────────────────────────────────────────────────────────────

    @Test
    void theShippedShapeLoads() {
        Tree tree = built(wood(), stone());
        assertEquals(Set.of(WOOD), tree.roots());
        assertEquals(List.of(WOOD, STONE), tree.nodes().stream().map(Node::id).toList());
    }

    @Test
    void aParentThatIsNotThereIsRefused() {
        Node orphan = node(STONE, NodeKind.CORE, List.of("test:wod"), Requirements.NONE, List.of(), Set.of());
        assertTrue(mentions(refused(wood(), orphan), STONE, "parent test:wod names no node"));
    }

    @Test
    void aCycleIsRefused() {
        Node a = node("test:a", NodeKind.SIDE, List.of("test:b"), Requirements.NONE, List.of(), Set.of());
        Node b = node("test:b", NodeKind.SIDE, List.of("test:a"), Requirements.NONE, List.of(), Set.of());
        List<String> errors = refused(wood(), a, b);
        assertTrue(mentions(errors, "test:a", "cycle") && mentions(errors, "test:b", "cycle"));
    }

    @Test
    void aCoreNodeNeverHangsOffASideOne() {
        Node fishing = node("test:fishing", NodeKind.SIDE, List.of(WOOD), Requirements.NONE,
                List.of(), Set.of());
        Node copper = node(COPPER, NodeKind.CORE, List.of("test:fishing"), Requirements.NONE,
                List.of(), Set.of());
        assertTrue(mentions(refused(wood(), fishing, copper), COPPER, "side parent test:fishing"));
    }

    @Test
    void aRequirementComesFromAnAncestor() {
        Node side = node("test:fishing", NodeKind.SIDE, List.of(WOOD), Requirements.NONE,
                List.of(direction("test:fishing", "area", 0)), Set.of());
        Node copper = node(COPPER, NodeKind.CORE, List.of(WOOD),
                new Requirements(ref("test:fishing", "area"), List.of(), 0), List.of(), Set.of());
        assertTrue(mentions(refused(wood(), side, copper), COPPER,
                "requires test:fishing/area, which is not an ancestor's"));
    }

    @Test
    void aRequirementNamesADirectionItsNodePursues() {
        Node copper = node(COPPER, NodeKind.CORE, List.of(WOOD),
                new Requirements(ref(WOOD, "food"), List.of(), 0), List.of(), Set.of());
        assertTrue(mentions(refused(wood(), copper), COPPER, "test:wood does not pursue"));
    }

    @Test
    void aPoolCannotBeAskedForMoreThanItHolds() {
        Node copper = node(COPPER, NodeKind.CORE, List.of(WOOD),
                new Requirements(null, List.of(ref(WOOD, "area")), 2), List.of(), Set.of());
        assertTrue(mentions(refused(wood(), copper), COPPER, "asks for 2 of a pool of 1"));
    }

    @Test
    void aRootIsAlwaysReachedAndSoRequiresNothing() {
        Node root = node(WOOD, NodeKind.CORE, List.of(),
                new Requirements(ref(WOOD, "wood"), List.of(), 0),
                List.of(direction(WOOD, "wood", 64)), Set.of());
        assertTrue(mentions(refused(root), WOOD, "a root is always reached"));
    }

    @Test
    void oneLineOnTwoUnrelatedNodesIsRefused() {
        Node a = node("test:a", NodeKind.SIDE, List.of(WOOD), Requirements.NONE,
                List.of(direction("test:a", "wood", 10)), Set.of());
        Node b = node("test:b", NodeKind.SIDE, List.of(WOOD), Requirements.NONE,
                List.of(direction("test:b", "wood", 20)), Set.of());
        assertTrue(mentions(refused(wood(), a, b), "test:b", "neither comes before the other"));
    }

    @Test
    void aLineNoRegisteredCodeMeansIsRefused() {
        Node copper = node(COPPER, NodeKind.CORE, List.of(WOOD), Requirements.NONE,
                List.of(direction(COPPER, "smelt", 1)), Set.of());
        assertTrue(mentions(refused(wood(), copper), COPPER, "smelt, which is no line"));
    }

    @Test
    void aDirectionCannotSeekWhatOnlyALaterNodeOpens() {
        // The only log there is belongs to copper, below stone — so stone's wood line asks for
        // something none of stone's ancestors open.
        Set<String> oneLog = Set.of("minecraft:oak_log");
        Node copper = node(COPPER, NodeKind.CORE, List.of(STONE), Requirements.NONE, List.of(),
                Set.of("minecraft:oak_log"));
        Tree.Built built = Tree.build(List.of(wood(), stone(), copper), oneLog, key -> false);
        assertTrue(mentions(built.errors(), STONE, "goes after logs"));

        Tree.Built open = Tree.build(List.of(wood(), stone()), oneLog, key -> false);
        assertTrue(open.errors().isEmpty(), "an ungated log is something any node may ask for");
    }

    @Test
    void anActNothingPerformsIsOnlyAWarning() {
        Node copper = new Node(COPPER, NodeKind.CORE, List.of(WOOD), true, Requirements.NONE,
                List.of(), Set.of(), Set.of("test:enchant"));
        Tree.Built built = Tree.build(List.of(wood(), copper), ITEMS, key -> false);
        assertNotNull(built.tree());
        assertTrue(built.warnings().stream().anyMatch(w -> w.contains("test:enchant")));
    }

    // ── a checked tree ──────────────────────────────────────────────────────────────────────

    @Test
    void theDeepestReachedVersionOfALineIsInForce() {
        Tree tree = built(wood(), stone());
        assertEquals(List.of(64), counts(tree.inForce(Set.of()), "wood"));
        assertEquals(List.of(256), counts(tree.inForce(Set.of(STONE)), "wood"),
                "reaching stone supersedes the wood age's version, completed or not");
        assertEquals(2, tree.inForce(Set.of(STONE)).size(), "and area stays in force from wood");
    }

    private static List<Integer> counts(List<Direction> inForce, String line) {
        return inForce.stream().filter(d -> d.line().equals(line)).map(Direction::count).toList();
    }

    @Test
    void aNodeUnlocksOnItsKeyAndEnoughOfItsPool() {
        Tree tree = built(wood(), stone());
        assertEquals(List.of(), tree.unlocked(Set.of(), Set.of(ref(WOOD, "wood"))), "the pool is short");
        assertEquals(List.of(), tree.unlocked(Set.of(), Set.of(ref(WOOD, "area"))), "no key");
        assertEquals(List.of(STONE),
                tree.unlocked(Set.of(), Set.of(ref(WOOD, "wood"), ref(WOOD, "area"))));
        assertEquals(List.of(),
                tree.unlocked(Set.of(STONE), Set.of(ref(WOOD, "wood"), ref(WOOD, "area"))),
                "a reached node is never reached again");
    }

    @Test
    void anItemIsGatedOnlyByTheNodesThatOpenIt() {
        Tree tree = built(wood(), stone());
        assertEquals(Optional.of(STONE),
                tree.lacksForItem("minecraft:stone_pickaxe", Set.of()).map(Node::id));
        assertEquals(Optional.empty(), tree.lacksForItem("minecraft:stone_pickaxe", Set.of(STONE)));
        assertEquals(Optional.empty(), tree.lacksForItem("minecraft:wooden_pickaxe", Set.of()),
                "the root's items: every body has the root");
        assertEquals(Optional.empty(), tree.lacksForItem("minecraft:iron_pickaxe", Set.of()),
                "an item no node names is not gated");
    }

    @Test
    void theAgeIsTheDeepestCoreNode() {
        Tree tree = built(wood(), stone());
        assertEquals(WOOD, tree.age(Set.of()).orElseThrow().id());
        assertEquals(STONE, tree.age(Set.of(STONE)).orElseThrow().id());
    }

    @Test
    void workTowardAnAgeOutbidsUpkeep() {
        Tree tree = built(wood(), stone());
        Direction stock = tree.node(WOOD).orElseThrow().directions().get(1);
        assertEquals(Direction.TOWARD_CORE, tree.priorityOf(stock, Set.of()));
        assertEquals(Direction.UPKEEP, tree.priorityOf(stock, Set.of(STONE)),
                "once stone is reached, the wood age's stock leads nowhere new");
        Direction set = new Direction(ref(WOOD, "wood"), 64, 0.9);
        assertEquals(0.9, tree.priorityOf(set, Set.of()), "the table's own number wins");
    }

    @Test
    void aGrantBringsWhatTheNodeNeedsAboveIt() {
        Node copper = node(COPPER, NodeKind.CORE, List.of(STONE), Requirements.NONE, List.of(), Set.of());
        Tree tree = built(wood(), stone(), copper);
        assertEquals(new ArrayList<>(List.of(COPPER, STONE)),
                new ArrayList<>(tree.grantClosure(COPPER, Set.of())));
        assertEquals(Set.of(COPPER), tree.grantClosure(COPPER, Set.of(STONE)));
        assertEquals(Set.of(STONE, COPPER), tree.descendantsOf(WOOD));
    }
}
