package dev.luizloyola.autarkia.core.bp;

import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

/**
 * The node types a blueprint may declare — an open registry, canonical per key. A type earns its
 * place when a behaviour knows how to use it, so bp 1 ships {@code path_node} alone (Luiz).
 */
public final class NodeTypes {

    public static final String PATH_NODE = "path_node";

    private static final Set<String> KNOWN = Collections.synchronizedSet(new TreeSet<>(Set.of(PATH_NODE)));

    private NodeTypes() {
    }

    public static void register(String type) {
        if (!type.matches("[a-z0-9_]+")) {
            throw new IllegalArgumentException("a node type is one snake_case word: " + type);
        }
        KNOWN.add(type);
    }

    public static boolean isKnown(String type) {
        return KNOWN.contains(type);
    }

    public static Set<String> known() {
        synchronized (KNOWN) {
            return Set.copyOf(KNOWN);
        }
    }
}
