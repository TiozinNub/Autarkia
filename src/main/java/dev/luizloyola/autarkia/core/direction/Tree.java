package dev.luizloyola.autarkia.core.direction;

import dev.luizloyola.anima.core.inv.ItemSpec;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * The research tree without research: nodes a party reaches by doing things, each opening items and
 * acts. Immutable, and only ever built through {@link #build}, which refuses a table that breaks a
 * rule — so everything below may assume a checked one.
 *
 * <p>Nodes are held in topological order, parents first. Several answers lean on that: the version
 * of a line in force is the last one reached in that order, and one pass is enough to find every
 * node a set of checkpoints unlocks.
 */
public final class Tree {

    /** No nodes: nothing is gated and nothing is pursued. What runs until a table loads. */
    public static final Tree EMPTY = new Tree(List.of(), Map.of());

    private final Map<String, Node> nodes;
    private final Map<String, Set<String>> ancestors;
    private final Map<String, Set<String>> openersByItem = new HashMap<>();
    private final Map<String, Set<String>> openersByAct = new HashMap<>();
    private final Set<String> roots = new LinkedHashSet<>();

    private Tree(List<Node> sorted, Map<String, Set<String>> ancestors) {
        Map<String, Node> byId = new LinkedHashMap<>();
        for (Node node : sorted) {
            byId.put(node.id(), node);
            if (node.isRoot()) {
                roots.add(node.id());
            }
            for (String item : node.items()) {
                openersByItem.computeIfAbsent(item, k -> new LinkedHashSet<>()).add(node.id());
            }
            for (String act : node.acts()) {
                openersByAct.computeIfAbsent(act, k -> new LinkedHashSet<>()).add(node.id());
            }
        }
        this.nodes = byId;
        this.ancestors = ancestors;
    }

    /**
     * What a load came to: a tree, or every rule it broke, each naming its node. Warnings never
     * refuse a table.
     */
    public record Built(@Nullable Tree tree, List<String> errors, List<String> warnings) {
    }

    /**
     * Checks a table and builds it, refusing one that breaks a rule:
     * <ul>
     *   <li>a parent, or a requirement, naming nothing there is;</li>
     *   <li>a cycle;</li>
     *   <li>a core node with a side parent, or taking a requirement from a side node;</li>
     *   <li>a requirement asking for more than the node's ancestors open — one that is not an
     *       ancestor's Direction, or a Direction whose line goes after something neither its node
     *       nor any ancestor lets a body have;</li>
     *   <li>two versions of one line where neither node comes before the other, so nothing could
     *       say which is in force.</li>
     * </ul>
     *
     * <p>"A node no path reaches" needs no rule of its own: in an acyclic table whose parents all
     * exist, walking up from any node ends at a root.
     *
     * @param items every item id the game knows — what a line's spec can be matched against
     * @param acts  whether an act key is declared; an unknown one is only a warning, since a
     *              datapack may name the acts of a mod that is not installed
     */
    public static Built build(Collection<Node> declared, Set<String> items, Predicate<String> acts) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<String, Node> byId = new LinkedHashMap<>();
        for (Node node : declared) {
            if (byId.putIfAbsent(node.id(), node) != null) {
                errors.add(node.id() + ": declared twice");
            }
        }
        for (Node node : byId.values()) {
            for (String parent : node.parents()) {
                if (!byId.containsKey(parent)) {
                    errors.add(node.id() + ": parent " + parent + " names no node");
                }
            }
        }
        if (!errors.isEmpty()) {
            return new Built(null, errors, warnings);
        }
        List<Node> sorted = topological(byId, errors);
        if (!errors.isEmpty()) {
            return new Built(null, errors, warnings);
        }
        Map<String, Set<String>> ancestors = new HashMap<>();
        for (Node node : sorted) {
            Set<String> above = new LinkedHashSet<>();
            for (String parent : node.parents()) {
                above.add(parent);
                above.addAll(ancestors.get(parent));
            }
            ancestors.put(node.id(), above);
        }
        Tree tree = new Tree(sorted, ancestors);
        tree.checkKinds(errors);
        tree.checkDirections(items, errors);
        tree.checkRequirements(errors);
        for (Node node : sorted) {
            for (String act : node.acts()) {
                if (!acts.test(act)) {
                    warnings.add(node.id() + ": opens act " + act + ", which nothing performs yet");
                }
            }
        }
        return errors.isEmpty() ? new Built(tree, errors, warnings) : new Built(null, errors, warnings);
    }

    /** Kahn's order, parents first; whatever is left over sits on a cycle. */
    private static List<Node> topological(Map<String, Node> byId, List<String> errors) {
        Map<String, Integer> waiting = new HashMap<>();
        Map<String, List<String>> children = new HashMap<>();
        for (Node node : byId.values()) {
            waiting.put(node.id(), new LinkedHashSet<>(node.parents()).size());
            for (String parent : new LinkedHashSet<>(node.parents())) {
                children.computeIfAbsent(parent, k -> new ArrayList<>()).add(node.id());
            }
        }
        Deque<String> ready = new ArrayDeque<>();
        for (Node node : byId.values()) {
            if (waiting.get(node.id()) == 0) {
                ready.add(node.id());
            }
        }
        List<Node> sorted = new ArrayList<>();
        while (!ready.isEmpty()) {
            String id = ready.poll();
            sorted.add(byId.get(id));
            for (String child : children.getOrDefault(id, List.of())) {
                if (waiting.merge(child, -1, Integer::sum) == 0) {
                    ready.add(child);
                }
            }
        }
        for (Node node : byId.values()) {
            if (waiting.get(node.id()) > 0) {
                errors.add(node.id() + ": part of a cycle");
            }
        }
        return sorted;
    }

    private void checkKinds(List<String> errors) {
        for (Node node : nodes.values()) {
            if (node.kind() != NodeKind.CORE) {
                continue;
            }
            for (String parent : node.parents()) {
                if (nodes.get(parent).kind() == NodeKind.SIDE) {
                    errors.add(node.id() + ": a core node has side parent " + parent);
                }
            }
        }
    }

    private void checkDirections(Set<String> items, List<String> errors) {
        Map<String, List<Node>> byLine = new LinkedHashMap<>();
        for (Node node : nodes.values()) {
            Set<String> seen = new HashSet<>();
            for (Direction direction : node.directions()) {
                if (!direction.id().node().equals(node.id())) {
                    errors.add(node.id() + ": declares a direction of " + direction.id().node());
                    continue;
                }
                if (!seen.add(direction.line())) {
                    errors.add(node.id() + ": pursues " + direction.line() + " twice");
                    continue;
                }
                byLine.computeIfAbsent(direction.line(), k -> new ArrayList<>()).add(node);
                Optional<DirectionLine> line = Lines.byId(direction.line());
                if (line.isEmpty()) {
                    errors.add(node.id() + ": pursues " + direction.line() + ", which is no line");
                    continue;
                }
                line.get().check(direction).ifPresent(problem ->
                        errors.add(node.id() + ": " + direction.line() + " " + problem));
                Optional<ItemSpec> seeks = line.get().seeks();
                if (seeks.isPresent() && !anyOpenTo(node.id(), seeks.get(), items)) {
                    errors.add(node.id() + ": " + direction.line() + " goes after " + seeks.get().name()
                            + ", which neither this node nor its ancestors open");
                }
            }
        }
        byLine.forEach((line, pursuing) -> {
            for (int i = 0; i < pursuing.size(); i++) {
                for (int j = i + 1; j < pursuing.size(); j++) {
                    String a = pursuing.get(i).id();
                    String b = pursuing.get(j).id();
                    if (!ancestors.get(a).contains(b) && !ancestors.get(b).contains(a)) {
                        errors.add(b + ": pursues " + line + " as " + a + " does, and neither "
                                + "comes before the other");
                    }
                }
            }
        });
    }

    /** Whether some item the spec names is ungated, or opened by the node or an ancestor. */
    private boolean anyOpenTo(String nodeId, ItemSpec spec, Set<String> items) {
        Set<String> allowed = new HashSet<>(ancestors.get(nodeId));
        allowed.add(nodeId);
        for (String item : items) {
            if (!spec.matches(item)) {
                continue;
            }
            Set<String> openers = openersByItem.getOrDefault(item, Set.of());
            if (openers.isEmpty() || openers.stream().anyMatch(allowed::contains)) {
                return true;
            }
        }
        return false;
    }

    private void checkRequirements(List<String> errors) {
        for (Node node : nodes.values()) {
            Requirements needs = node.requirements();
            if (node.isRoot()) {
                if (!needs.isEmpty()) {
                    errors.add(node.id() + ": a root is always reached, so it can require nothing");
                }
                continue;
            }
            if (needs.of() < 0 || needs.of() > needs.pool().size()) {
                errors.add(node.id() + ": asks for " + needs.of() + " of a pool of "
                        + needs.pool().size());
            }
            List<DirectionId> all = new ArrayList<>(needs.pool());
            if (needs.key() != null) {
                all.add(needs.key());
            }
            for (DirectionId id : all) {
                Node from = nodes.get(id.node());
                if (from == null || !ancestors.get(node.id()).contains(id.node())) {
                    errors.add(node.id() + ": requires " + id + ", which is not an ancestor's");
                } else if (from.directions().stream().noneMatch(d -> d.id().equals(id))) {
                    errors.add(node.id() + ": requires " + id + ", which " + id.node()
                            + " does not pursue");
                } else if (node.kind() == NodeKind.CORE && from.kind() == NodeKind.SIDE) {
                    errors.add(node.id() + ": a core node takes a requirement from side node "
                            + id.node());
                }
            }
        }
    }

    // ── reading a checked tree ───────────────────────────────────────────────────────────────

    /** Every node, parents first. */
    public Collection<Node> nodes() {
        return nodes.values();
    }

    public Optional<Node> node(String id) {
        return Optional.ofNullable(nodes.get(id));
    }

    public boolean isEmpty() {
        return nodes.isEmpty();
    }

    /** The nodes with no parents — always reached, since a fresh body already does what they open. */
    public Set<String> roots() {
        return roots;
    }

    /** A stored set of reached nodes, with the roots every body has. */
    public Set<String> withRoots(Set<String> stored) {
        Set<String> all = new LinkedHashSet<>(roots);
        all.addAll(stored);
        return all;
    }

    public Set<String> ancestorsOf(String id) {
        return ancestors.getOrDefault(id, Set.of());
    }

    /** Every node below this one, however far. */
    public Set<String> descendantsOf(String id) {
        Set<String> below = new LinkedHashSet<>();
        for (Node node : nodes.values()) {
            if (ancestorsOf(node.id()).contains(id)) {
                below.add(node.id());
            }
        }
        return below;
    }

    /** Whether any node opens this item — the cheap question before anybody's nodes are looked up. */
    public boolean gatesItem(String item) {
        return openersByItem.containsKey(item);
    }

    public boolean gatesAct(String act) {
        return openersByAct.containsKey(act);
    }

    /**
     * The node that must be reached before a body may seek or make this item, or empty when this
     * body may — because no node opens the item, or one it has reached does. An item in no node is
     * not gated, so a modded item works until somebody places it.
     */
    public Optional<Node> lacksForItem(String item, Set<String> reached) {
        return lacks(openersByItem.get(item), reached);
    }

    /** The same question for an act. */
    public Optional<Node> lacksForAct(String act, Set<String> reached) {
        return lacks(openersByAct.get(act), reached);
    }

    private Optional<Node> lacks(@Nullable Set<String> openers, Set<String> reached) {
        if (openers == null) {
            return Optional.empty();
        }
        Set<String> all = withRoots(reached);
        for (String opener : openers) {
            if (all.contains(opener)) {
                return Optional.empty();
            }
        }
        // The first opener in the tree's own order, so the node a refusal names is the nearest.
        for (Node node : nodes.values()) {
            if (openers.contains(node.id())) {
                return Optional.of(node);
            }
        }
        return Optional.empty();
    }

    /** The party's age: its deepest core node, by how many nodes lie above it. */
    public Optional<Node> age(Set<String> reached) {
        Set<String> all = withRoots(reached);
        Node deepest = null;
        for (Node node : nodes.values()) {
            if (node.kind() == NodeKind.CORE && all.contains(node.id())
                    && (deepest == null
                            || ancestorsOf(node.id()).size() >= ancestorsOf(deepest.id()).size())) {
                deepest = node;
            }
        }
        return Optional.ofNullable(deepest);
    }

    /**
     * The Directions in force: for each line, the version on the deepest node reached. Reaching the
     * node of a later version is what supersedes an earlier one, completed or not.
     */
    public List<Direction> inForce(Set<String> reached) {
        Set<String> all = withRoots(reached);
        Map<String, Direction> byLine = new LinkedHashMap<>();
        for (Node node : nodes.values()) {
            if (all.contains(node.id())) {
                for (Direction direction : node.directions()) {
                    byLine.put(direction.line(), direction);
                }
            }
        }
        return List.copyOf(byLine.values());
    }

    /** Whether this node's parents are reached — all of them or any one, as it says. */
    public boolean parentsMet(Node node, Set<String> reached) {
        Set<String> all = withRoots(reached);
        if (node.isRoot()) {
            return true;
        }
        return node.needsAll()
                ? all.containsAll(node.parents())
                : node.parents().stream().anyMatch(all::contains);
    }

    /**
     * Every node these checkpoints unlock, in the order they unlock — one pass, since parents come
     * first. Never a node already reached: evolution never goes down, and never repeats.
     */
    public List<String> unlocked(Set<String> reached, Set<DirectionId> checkpoints) {
        Set<String> now = withRoots(reached);
        List<String> gained = new ArrayList<>();
        for (Node node : nodes.values()) {
            if (now.contains(node.id())) {
                continue;
            }
            if (parentsMet(node, now) && node.requirements().metBy(checkpoints)) {
                now.add(node.id());
                gained.add(node.id());
            }
        }
        return gained;
    }

    /**
     * What an operator's grant adds: the node, and whatever above it has to be reached for it to
     * make sense — every parent when it needs all, the first when it needs any and none is reached.
     */
    public Set<String> grantClosure(String id, Set<String> reached) {
        Set<String> out = new LinkedHashSet<>();
        Set<String> have = withRoots(reached);
        Deque<String> todo = new ArrayDeque<>(List.of(id));
        while (!todo.isEmpty()) {
            String next = todo.poll();
            if (have.contains(next) || !out.add(next)) {
                continue;
            }
            Node node = nodes.get(next);
            if (node.needsAll()) {
                todo.addAll(node.parents());
            } else if (!node.parents().isEmpty()
                    && node.parents().stream().noneMatch(p -> have.contains(p) || out.contains(p))) {
                todo.add(node.parents().get(0));
            }
        }
        return out;
    }

    /**
     * What a Direction bids: the table's own number when it sets one, else by the most important
     * node it leads to that is not reached yet — core before side, and upkeep when it leads nowhere.
     */
    public double priorityOf(Direction direction, Set<String> reached) {
        if (direction.priority() != null) {
            return direction.priority();
        }
        Set<String> all = withRoots(reached);
        boolean side = false;
        for (Node node : nodes.values()) {
            if (all.contains(node.id()) || !node.requirements().mentions(direction.id())) {
                continue;
            }
            if (node.kind() == NodeKind.CORE) {
                return Direction.TOWARD_CORE;
            }
            side = true;
        }
        return side ? Direction.TOWARD_SIDE : Direction.UPKEEP;
    }
}
