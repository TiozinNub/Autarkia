package dev.luizloyola.autarkia.core.brain.task;

import java.util.List;

/**
 * The goal "get a tree felled", one method today ({@link ChopKnownTree}). {@code ObtainItem}
 * (work-loop ladder step 3) will absorb that method into its own list; callers never notice.
 */
public final class ChopNearestTree implements CompoundTask {
    private final List<Method> methods = List.of(new ChopKnownTree());

    @Override
    public List<Method> methods() {
        return methods;
    }

    @Override
    public String describe() {
        return "chop nearest tree";
    }
}
