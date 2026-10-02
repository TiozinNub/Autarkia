package dev.luizloyola.autarkia.core.direction;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
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
    private final Map<DirectionId, Stall> stalls = new LinkedHashMap<>();

    /**
     * A line's work getting nowhere: its {@link DirectionLine#measure} at the last beat, and the
     * beats since it last rose that nobody was working the line's work.
     */
    public record Stall(int last, int beats) {

        /**
         * Fifteen beats, 3,000 ticks of nobody on the work and nothing gained: past a cook's round
         * trip and the first two of its failure back-off's retries (600, then 1,200), so a cook that
         * fails once or waits a while for a taker is not called stuck. Only a rise ends it, so a
         * stuck line does not flip back on the next beat.
         */
        public static final int STUCK_BEATS = 15;

        public boolean stuck() {
            return beats >= STUCK_BEATS;
        }
    }

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

    public Map<DirectionId, Stall> stalls() {
        return Collections.unmodifiableMap(stalls);
    }

    public Optional<Stall> stall(DirectionId id) {
        return Optional.ofNullable(stalls.get(id));
    }

    /** Whether this changed what is kept; {@code null} forgets it. */
    public boolean stall(DirectionId id, @Nullable Stall now) {
        return !java.util.Objects.equals(now == null ? stalls.remove(id) : stalls.put(id, now), now);
    }

    /** Whether there is anything worth saving. */
    public boolean isEmpty() {
        return reached.isEmpty() && checkpoints.isEmpty() && home == null && stalls.isEmpty();
    }
}
