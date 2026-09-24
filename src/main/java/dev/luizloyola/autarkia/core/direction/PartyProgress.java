package dev.luizloyola.autarkia.core.direction;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * How far one party has climbed: the nodes it has reached, the checkpoints it has recorded, and its
 * HOME.
 *
 * <p><b>The reached set is stored, not re-derived</b>, so editing the node table can never take a
 * node away, and nothing here removes one — {@link #revoke} is the operator's, never the game's.
 */
public final class PartyProgress {

    private final Set<String> reached = new LinkedHashSet<>();
    private final Set<DirectionId> checkpoints = new LinkedHashSet<>();
    private @Nullable Home home;

    public Set<String> reached() {
        return Collections.unmodifiableSet(reached);
    }

    public Set<DirectionId> checkpoints() {
        return Collections.unmodifiableSet(checkpoints);
    }

    public @Nullable Home home() {
        return home;
    }

    public void home(@Nullable Home now) {
        this.home = now;
    }

    /** Whether this node is newly reached. */
    public boolean reach(String node) {
        return reached.add(node);
    }

    /** Whether this completion is new — completion is recorded once. */
    public boolean complete(DirectionId id) {
        return checkpoints.add(id);
    }

    /** An operator's revoke. The one way anything leaves a reached set. */
    public boolean revoke(String node) {
        return reached.remove(node);
    }

    /** An operator's revoke of a checkpoint that earned a revoked node. */
    public boolean forget(DirectionId id) {
        return checkpoints.remove(id);
    }

    /** Whether there is anything worth saving. */
    public boolean isEmpty() {
        return reached.isEmpty() && checkpoints.isEmpty() && home == null;
    }
}
