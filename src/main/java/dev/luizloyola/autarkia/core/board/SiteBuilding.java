package dev.luizloyola.autarkia.core.board;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.board.WorkItem;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Choose where a building goes, and claim it — work no body does. A line posts it, as lines post
 * everything; the mod reads the ground and the routes on the server and closes it once the party's
 * record of the site exists (docs/superpowers/specs/2026-10-01-house-site-design.md, rung 4). It
 * offers nothing to anybody.
 */
public final class SiteBuilding implements PartyProject {

    private final String blueprint;
    private final Map<String, String> variants;
    private final double priority;
    private boolean done;

    public SiteBuilding(String blueprint, Map<String, String> variants, double priority) {
        this.blueprint = Objects.requireNonNull(blueprint, "blueprint");
        this.variants = Map.copyOf(variants);
        this.priority = priority;
    }

    public String blueprint() {
        return blueprint;
    }

    public Map<String, String> variants() {
        return variants;
    }

    /** The site is chosen and recorded: the project closes. */
    public void sited() {
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
        return "choose where " + blueprint + " goes" + (done ? " — done" : "");
    }

    public record State(String blueprint, Map<String, String> variants, double priority, boolean done)
            implements ProjectState {
        @Override
        public String type() {
            return "site_building";
        }
    }

    @Override
    public State snapshot() {
        return new State(blueprint, variants, priority, done);
    }

    public static Optional<SiteBuilding> restore(State state) {
        SiteBuilding project = new SiteBuilding(state.blueprint(), state.variants(), state.priority());
        project.done = state.done();
        return Optional.of(project);
    }

    public static final ProjectType TYPE = new ProjectType() {
        @Override
        public String id() {
            return "site_building";
        }

        @Override
        public Optional<SiteBuilding> restore(ProjectState state, long now) {
            return state instanceof State s ? SiteBuilding.restore(s) : Optional.empty();
        }
    };
}
