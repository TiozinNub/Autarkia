package dev.luizloyola.autarkia.core.direction;

import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * What unlocks a node: a key, plus {@code of} of a pool, each a Direction of an ancestor. A root
 * has {@link #NONE}.
 */
public record Requirements(@Nullable DirectionId key, List<DirectionId> pool, int of) {

    public static final Requirements NONE = new Requirements(null, List.of(), 0);

    public Requirements {
        pool = List.copyOf(pool);
    }

    public boolean metBy(Set<DirectionId> checkpoints) {
        return (key == null || checkpoints.contains(key)) && poolMet(checkpoints) >= of;
    }

    /** How many of the pool are checkpoints already. */
    public int poolMet(Set<DirectionId> checkpoints) {
        int met = 0;
        for (DirectionId id : pool) {
            if (checkpoints.contains(id)) {
                met++;
            }
        }
        return met;
    }

    public boolean mentions(DirectionId id) {
        return id.equals(key) || pool.contains(id);
    }

    public boolean isEmpty() {
        return key == null && pool.isEmpty();
    }
}
