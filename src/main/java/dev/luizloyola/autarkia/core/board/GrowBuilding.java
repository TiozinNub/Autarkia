package dev.luizloyola.autarkia.core.board;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.board.WorkItem;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Grow a standing building into a new selection of its variants, for a station it lacks — work no
 * body does, as {@link SiteBuilding} is. A line posts it; the mod takes down what stands in the
 * cells the growth changes, posts the build of the diff, and closes it
 * (docs/superpowers/specs/2026-10-02-house-grows-design.md).
 *
 * @param station the item id of the station the growth adds, which names whose want it is
 */
public final class GrowBuilding implements PartyProject {

    private final UUID structure;
    private final Map<String, String> variants;
    private final String station;
    private final double priority;
    private boolean done;

    public GrowBuilding(UUID structure, Map<String, String> variants, String station, double priority) {
        this.structure = Objects.requireNonNull(structure, "structure");
        this.variants = Map.copyOf(variants);
        this.station = Objects.requireNonNull(station, "station");
        this.priority = priority;
    }

    public UUID structure() {
        return structure;
    }

    public Map<String, String> variants() {
        return variants;
    }

    public String station() {
        return station;
    }

    /** The growth is under way, or cannot be: the project closes. */
    public void taken() {
        done = true;
    }

    @Override
    public double priority() {
        return priority;
    }

    @Override
    public List<WorkItem> open() {
        return List.of();
    }

    @Override
    public boolean finished() {
        return done;
    }

    @Override
    public void tick(long now) {
    }

    @Override
    public void completed(WorkItem item, BrainContext ctx) {
    }

    @Override
    public void failed(WorkItem item, BrainContext ctx) {
    }

    @Override
    public Optional<WorkKey> keyOf(WorkItem item) {
        return Optional.empty();
    }

    @Override
    public Optional<WorkItem> itemFor(WorkKey key) {
        return Optional.empty();
    }

    @Override
    public String describe() {
        return "grow a building for a " + station + (done ? " — done" : "");
    }

    public record State(UUID structure, Map<String, String> variants, String station, double priority, boolean done)
            implements ProjectState {
        @Override
        public String type() {
            return "grow_building";
        }
    }

    @Override
    public State snapshot() {
        return new State(structure, variants, station, priority, done);
    }

    public static Optional<GrowBuilding> restore(State state) {
        GrowBuilding project = new GrowBuilding(state.structure(), state.variants(), state.station(), state.priority());
        project.done = state.done();
        return Optional.of(project);
    }

    public static final ProjectType TYPE = new ProjectType() {
        @Override
        public String id() {
            return "grow_building";
        }

        @Override
        public Optional<GrowBuilding> restore(ProjectState state, long now) {
            return state instanceof State s ? GrowBuilding.restore(s) : Optional.empty();
        }
    };
}
